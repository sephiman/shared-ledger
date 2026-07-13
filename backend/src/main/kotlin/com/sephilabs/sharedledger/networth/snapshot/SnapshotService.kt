package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.catalog.AssetClassRepository
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.cash.CashAdjustment
import com.sephilabs.sharedledger.networth.cash.CashAdjustmentRepository
import com.sephilabs.sharedledger.networth.cash.CashEstimateService
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.notification.NotifyAction
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.portfolio.PortfolioValuationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

@Service
class SnapshotService(
    private val snapshots: SnapshotRepository,
    private val liabilities: LiabilityRepository,
    private val assets: AssetRepository,
    private val assetClasses: AssetClassRepository,
    private val portfolioValuation: PortfolioValuationService,
    private val namedValues: com.sephilabs.sharedledger.networth.NamedValueResolver,
    private val cashEstimates: CashEstimateService,
    private val cashAdjustments: CashAdjustmentRepository,
    private val metrics: AppMetrics,
    private val notifications: NotificationPublisher,
) {

    /** Computed value of each active named asset/liability at [date] — auto-fill source for the form. */
    @Transactional(readOnly = true)
    fun namedValuesAt(householdId: UUID, date: LocalDate): NamedValuesDto {
        val assetMap = assets.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId)
            .associate { it.id.toString() to Money.normalize(namedValues.assetValueAt(it.id, date) ?: BigDecimal.ZERO).toPlainString() }
        val liabMap = liabilities.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId)
            .associate { it.id.toString() to Money.normalize(namedValues.liabilityBalanceAt(it.id, date) ?: BigDecimal.ZERO).toPlainString() }
        val cash = cashEstimates.estimateAt(householdId, date).estimate?.toPlainString()
        return NamedValuesDto(assetMap, liabMap, cash)
    }

    @Transactional
    fun create(householdId: UUID, request: SnapshotRequest, by: User): SnapshotDto =
        createInternal(householdId, request, by.id, NotifyActor.Human(by.email))

    /**
     * Creates a snapshot on behalf of the scheduled job. Identical to [create] — same
     * autofill and freeze — except it is attributed to [ownerUserId] (the household owner,
     * since there is no acting user) and notified as a scheduled action.
     */
    @Transactional
    fun createScheduled(householdId: UUID, request: SnapshotRequest, ownerUserId: UUID): SnapshotDto =
        createInternal(householdId, request, ownerUserId, NotifyActor.Schedule(householdId))

    private fun createInternal(
        householdId: UUID,
        request: SnapshotRequest,
        createdByUserId: UUID,
        actor: NotifyActor,
    ): SnapshotDto {
        // One snapshot per (household, date) — the unique index enforces it; this gives a friendly
        // error instead of a raw 409 on the common (non-race) manual case. Edit the existing one instead.
        if (snapshots.existsByHouseholdIdAndSnapshotDate(householdId, request.snapshotDate)) {
            throw AppException.conflict("SNAPSHOT_DATE_EXISTS")
        }

        val expectedClasses = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }.toSet()
        val givenClasses = request.assets.map { it.assetClassCode }.toSet()
        if (!givenClasses.containsAll(expectedClasses)) throw AppException.badRequest("SNAPSHOT_MISSING_ASSET_VALUES")

        val activeLiabilities = liabilities.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId)
        val expectedLiabilities = activeLiabilities.map { it.id }.toSet()
        val givenLiabilities = request.liabilities.map { it.liabilityId }.toSet()
        if (!givenLiabilities.containsAll(expectedLiabilities)) throw AppException.badRequest("SNAPSHOT_MISSING_LIABILITY_BALANCES")

        val expectedNamedAssets = assets.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId).map { it.id }.toSet()
        val givenNamedAssets = request.namedAssets.map { it.assetId }.toSet()
        if (!givenNamedAssets.containsAll(expectedNamedAssets)) throw AppException.badRequest("SNAPSHOT_MISSING_NAMED_ASSET_VALUES")

        val previous = snapshots.findUpTo(householdId, request.snapshotDate.minusDays(1)).firstOrNull()
        if (!request.confirmLargeChanges && previous != null && hasLargeChange(previous, request)) {
            throw AppException.badRequest("SNAPSHOT_REQUIRES_DELTA_CONFIRMATION")
        }

        val snapshot = Snapshot(
            householdId = householdId,
            snapshotDate = request.snapshotDate,
            note = request.note,
            createdByUserId = createdByUserId,
            updatedByUserId = createdByUserId,
        )
        // Cash is estimated from its adjustment series + marked flows; feed that estimate into the
        // same "computed" detection used for portfolio classes. Null when there is no series yet.
        val cashEstimate = cashEstimates.estimateAt(householdId, request.snapshotDate).estimate
        val computedByClass = portfolioValuation.valuationAt(householdId, request.snapshotDate).byClass +
            (cashEstimate?.let { mapOf(CASH_CLASS to it) } ?: emptyMap())
        snapshot.assetValues = request.assets.map {
            toAssetValue(snapshot.id, it, computedByClass[it.assetClassCode])
        }.toMutableList()
        // Correcting cash re-anchors: a user-entered cash value (differing from the estimate) writes
        // a new adjustment at the snapshot date, so the estimate never becomes a desynced truth.
        reAnchorCashIfCorrected(householdId, request.snapshotDate, snapshot.assetValues, cashEstimate, createdByUserId)
        snapshot.liabilityBalances = request.liabilities.map {
            SnapshotLiabilityBalance(
                id = SnapshotLiabilityBalanceId(snapshot.id, it.liabilityId),
                balance = Money.normalize(it.balance),
            )
        }.toMutableList()
        snapshot.namedAssetValues = request.namedAssets.map {
            SnapshotNamedAssetValue(
                id = SnapshotNamedAssetValueId(snapshot.id, it.assetId),
                value = Money.normalize(it.value),
            )
        }.toMutableList()

        snapshots.save(snapshot)
        portfolioValuation.freezeValuations(snapshot.id, householdId, request.snapshotDate)
        metrics.snapshotCreated()
        val dto = toDto(snapshot)
        notifications.snapshot(dto, householdId, NotifyAction.CREATE, actor)
        return dto
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: SnapshotRequest, by: User): SnapshotDto {
        val snapshot = snapshots.findById(id).orElseThrow { AppException.notFound("SNAPSHOT_NOT_FOUND") }
        if (snapshot.householdId != householdId) throw AppException.notFound("SNAPSHOT_NOT_FOUND")

        // Moving a snapshot onto a date already taken by another snapshot would violate uniqueness.
        if (request.snapshotDate != snapshot.snapshotDate &&
            snapshots.existsByHouseholdIdAndSnapshotDate(householdId, request.snapshotDate)
        ) {
            throw AppException.conflict("SNAPSHOT_DATE_EXISTS")
        }

        val expectedClasses = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }.toSet()
        val givenClasses = request.assets.map { it.assetClassCode }.toSet()
        if (!givenClasses.containsAll(expectedClasses)) throw AppException.badRequest("SNAPSHOT_MISSING_ASSET_VALUES")

        snapshot.snapshotDate = request.snapshotDate
        snapshot.note = request.note
        snapshot.updatedByUserId = by.id

        // Same cash estimate merge as create so a cash row frozen as 'computed' re-validates on edit.
        // Editing an existing snapshot re-freezes without touching the series (re-anchor is create-only).
        val cashEstimate = cashEstimates.estimateAt(householdId, request.snapshotDate).estimate
        val computedByClass = portfolioValuation.valuationAt(householdId, request.snapshotDate).byClass +
            (cashEstimate?.let { mapOf(CASH_CLASS to it) } ?: emptyMap())
        snapshot.assetValues.clear()
        snapshot.assetValues.addAll(request.assets.map {
            toAssetValue(snapshot.id, it, computedByClass[it.assetClassCode])
        })
        snapshot.liabilityBalances.clear()
        snapshot.liabilityBalances.addAll(request.liabilities.map {
            SnapshotLiabilityBalance(
                id = SnapshotLiabilityBalanceId(snapshot.id, it.liabilityId),
                balance = Money.normalize(it.balance),
            )
        })
        snapshot.namedAssetValues.clear()
        snapshot.namedAssetValues.addAll(request.namedAssets.map {
            SnapshotNamedAssetValue(
                id = SnapshotNamedAssetValueId(snapshot.id, it.assetId),
                value = Money.normalize(it.value),
            )
        })

        snapshots.save(snapshot)
        // Re-freeze: past snapshots stay correctable, and a new date needs new valuations.
        portfolioValuation.freezeValuations(snapshot.id, householdId, request.snapshotDate)
        val dto = toDto(snapshot)
        notifications.snapshot(dto, householdId, NotifyAction.UPDATE, NotifyActor.Human(by.email))
        return dto
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val s = snapshots.findById(id).orElseThrow { AppException.notFound("SNAPSHOT_NOT_FOUND") }
        if (s.householdId != householdId) throw AppException.notFound("SNAPSHOT_NOT_FOUND")
        val dto = toDto(s)
        snapshots.delete(s)
        notifications.snapshot(dto, householdId, NotifyAction.DELETE, NotifyActor.Human(by.email))
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, from: LocalDate, to: LocalDate): List<SnapshotDto> =
        snapshots.findInRange(householdId, from, to).map { toDto(it) }

    @Transactional(readOnly = true)
    fun prefill(householdId: UUID): PrefillView {
        val previous = snapshots.findLatest(householdId)?.let { toDto(it) }
        val active = liabilities.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId).map { it.id }
        return PrefillView(previous, active)
    }

    @Transactional(readOnly = true)
    fun exportCsv(householdId: UUID): String {
        val all = snapshots.findAllOrdered(householdId)
        val liabilityNames = liabilities.findAllNamesIncludingDeleted(householdId).associate { it.id to it.name }
        val assetNames = assets.findAllNamesIncludingDeleted(householdId).associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append(Csv.row("date", "note", "kind", "key", "value"))
        for (s in all) {
            for (a in s.assetValues) {
                sb.append(Csv.row(s.snapshotDate, s.note, "asset", a.id.assetClassCode, Csv.decimal(a.value)))
            }
            for (a in s.namedAssetValues) {
                val name = assetNames[a.id.assetId] ?: a.id.assetId.toString()
                sb.append(Csv.row(s.snapshotDate, s.note, "named_asset", name, Csv.decimal(a.value)))
            }
            for (l in s.liabilityBalances) {
                val name = liabilityNames[l.id.liabilityId] ?: l.id.liabilityId.toString()
                sb.append(Csv.row(s.snapshotDate, s.note, "liability", name, Csv.decimal(l.balance)))
            }
        }
        return sb.toString()
    }

    /**
     * Writes a new cash adjustment at [snapshotDate] when the snapshot's cash value was corrected
     * away from the estimate — the hybrid re-anchor. Only fires when the series is already active
     * (an estimate existed); with no series, cash still behaves as a plain carried-over class and
     * the first anchor is created from the Cash sub-tab. Runs on create only.
     */
    private fun reAnchorCashIfCorrected(
        householdId: UUID,
        snapshotDate: LocalDate,
        assetValues: List<SnapshotAssetValue>,
        cashEstimate: BigDecimal?,
        userId: UUID,
    ) {
        if (cashEstimate == null) return
        val cash = assetValues.firstOrNull { it.id.assetClassCode == CASH_CLASS } ?: return
        if (cash.valueSource != VALUE_SOURCE_OVERRIDDEN) return
        cashAdjustments.save(
            CashAdjustment(
                householdId = householdId,
                adjustmentDate = snapshotDate,
                amount = cash.value,
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )
    }

    /**
     * Resolves the stored value and source for one asset class. An explicit 'computed'
     * uses the server-computed portfolio value (rejecting classes without holdings);
     * a null source is inferred: matching the computed value to the cent means computed.
     */
    private fun toAssetValue(
        snapshotId: UUID,
        input: AssetValueInput,
        computedForClass: BigDecimal?,
    ): SnapshotAssetValue {
        val source = when (input.valueSource) {
            null ->
                if (computedForClass != null && Money.normalize(input.value).compareTo(computedForClass) == 0) {
                    VALUE_SOURCE_COMPUTED
                } else {
                    VALUE_SOURCE_OVERRIDDEN
                }
            VALUE_SOURCE_COMPUTED -> {
                if (computedForClass == null) throw AppException.badRequest("SNAPSHOT_VALUE_SOURCE_INVALID")
                VALUE_SOURCE_COMPUTED
            }
            VALUE_SOURCE_OVERRIDDEN -> VALUE_SOURCE_OVERRIDDEN
            // Set only by the scheduled job when copying a manual class forward.
            VALUE_SOURCE_CARRIED_OVER -> VALUE_SOURCE_CARRIED_OVER
            else -> throw AppException.badRequest("SNAPSHOT_VALUE_SOURCE_INVALID")
        }
        val value = if (source == VALUE_SOURCE_COMPUTED) computedForClass!! else Money.normalize(input.value)
        return SnapshotAssetValue(
            id = SnapshotAssetValueId(snapshotId, input.assetClassCode),
            value = value,
            valueSource = source,
        )
    }

    fun toDto(snapshot: Snapshot): SnapshotDto {
        // Names are resolved including soft-deleted rows so historical snapshots never show a raw UUID.
        val assetNames = this.assets.findAllNamesIncludingDeleted(snapshot.householdId).associate { it.id to it.name }
        val liabilityNames = liabilities.findAllNamesIncludingDeleted(snapshot.householdId).associate { it.id to it.name }

        val classes = snapshot.assetValues.map { AssetValueDto(it.id.assetClassCode, it.value, it.valueSource) }
        val namedAssets = snapshot.namedAssetValues.map {
            NamedAssetValueDto(it.id.assetId, assetNames[it.id.assetId] ?: it.id.assetId.toString(), it.value)
        }.sortedBy { it.name.lowercase() }
        val liabilities = snapshot.liabilityBalances.map {
            LiabilityBalanceDto(
                it.id.liabilityId,
                liabilityNames[it.id.liabilityId] ?: it.id.liabilityId.toString(),
                it.balance,
            )
        }.sortedBy { it.liabilityName.lowercase() }

        val totalAssets = classes.fold(BigDecimal.ZERO) { acc, a -> acc + a.value } +
            namedAssets.fold(BigDecimal.ZERO) { acc, a -> acc + a.value }
        val totalLiabilities = liabilities.fold(BigDecimal.ZERO) { acc, l -> acc + l.balance }
        return SnapshotDto(
            id = snapshot.id,
            snapshotDate = snapshot.snapshotDate,
            note = snapshot.note,
            totalAssets = Money.normalize(totalAssets),
            totalLiabilities = Money.normalize(totalLiabilities),
            netWorth = Money.normalize(totalAssets - totalLiabilities),
            assets = classes,
            namedAssets = namedAssets,
            liabilities = liabilities,
            createdAt = snapshot.createdAt,
        )
    }

    private fun hasLargeChange(previous: Snapshot, request: SnapshotRequest): Boolean {
        val threshold = BigDecimal("0.5")
        val prevAssets = previous.assetValues.associate { it.id.assetClassCode to it.value }
        for (asset in request.assets) {
            val prev = prevAssets[asset.assetClassCode] ?: BigDecimal.ZERO
            if (changeExceeds(prev, asset.value, threshold)) return true
        }
        val prevLiab = previous.liabilityBalances.associate { it.id.liabilityId to it.balance }
        for (liab in request.liabilities) {
            val prev = prevLiab[liab.liabilityId] ?: BigDecimal.ZERO
            if (changeExceeds(prev, liab.balance, threshold)) return true
        }
        val prevNamed = previous.namedAssetValues.associate { it.id.assetId to it.value }
        for (named in request.namedAssets) {
            val prev = prevNamed[named.assetId] ?: BigDecimal.ZERO
            if (changeExceeds(prev, named.value, threshold)) return true
        }
        return false
    }

    private fun changeExceeds(prev: BigDecimal, next: BigDecimal, threshold: BigDecimal): Boolean {
        if (prev.signum() == 0 && next.signum() == 0) return false
        if (prev.signum() == 0) return next.signum() != 0
        val ratio = (next - prev).abs().divide(prev.abs(), 6, RoundingMode.HALF_EVEN)
        return ratio > threshold
    }
}
