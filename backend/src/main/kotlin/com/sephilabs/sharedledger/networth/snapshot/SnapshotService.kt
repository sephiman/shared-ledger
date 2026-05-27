package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.catalog.AssetClassRepository
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.notification.NotifyAction
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.observability.AppMetrics
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
    private val assetClasses: AssetClassRepository,
    private val metrics: AppMetrics,
    private val notifications: NotificationPublisher,
) {

    @Transactional
    fun create(householdId: UUID, request: SnapshotRequest, by: User): SnapshotDto {
        val expectedClasses = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }.toSet()
        val givenClasses = request.assets.map { it.assetClassCode }.toSet()
        if (!givenClasses.containsAll(expectedClasses)) throw AppException.badRequest("SNAPSHOT_MISSING_ASSET_VALUES")

        val activeLiabilities = liabilities.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId)
        val expectedLiabilities = activeLiabilities.map { it.id }.toSet()
        val givenLiabilities = request.liabilities.map { it.liabilityId }.toSet()
        if (!givenLiabilities.containsAll(expectedLiabilities)) throw AppException.badRequest("SNAPSHOT_MISSING_LIABILITY_BALANCES")

        val previous = snapshots.findUpTo(householdId, request.snapshotDate.minusDays(1)).firstOrNull()
        if (!request.confirmLargeChanges && previous != null && hasLargeChange(previous, request)) {
            throw AppException.badRequest("SNAPSHOT_REQUIRES_DELTA_CONFIRMATION")
        }

        val snapshot = Snapshot(
            householdId = householdId,
            snapshotDate = request.snapshotDate,
            note = request.note,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        snapshot.assetValues = request.assets.map {
            SnapshotAssetValue(
                id = SnapshotAssetValueId(snapshot.id, it.assetClassCode),
                value = Money.normalize(it.value),
            )
        }.toMutableList()
        snapshot.liabilityBalances = request.liabilities.map {
            SnapshotLiabilityBalance(
                id = SnapshotLiabilityBalanceId(snapshot.id, it.liabilityId),
                balance = Money.normalize(it.balance),
            )
        }.toMutableList()

        snapshots.save(snapshot)
        metrics.snapshotCreated()
        val dto = toDto(snapshot)
        notifications.snapshot(dto, householdId, NotifyAction.CREATE, NotifyActor.Human(by.email))
        return dto
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: SnapshotRequest, by: User): SnapshotDto {
        val snapshot = snapshots.findById(id).orElseThrow { AppException.notFound("SNAPSHOT_NOT_FOUND") }
        if (snapshot.householdId != householdId) throw AppException.notFound("SNAPSHOT_NOT_FOUND")

        val expectedClasses = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }.toSet()
        val givenClasses = request.assets.map { it.assetClassCode }.toSet()
        if (!givenClasses.containsAll(expectedClasses)) throw AppException.badRequest("SNAPSHOT_MISSING_ASSET_VALUES")

        snapshot.snapshotDate = request.snapshotDate
        snapshot.note = request.note
        snapshot.updatedByUserId = by.id

        snapshot.assetValues.clear()
        snapshot.assetValues.addAll(request.assets.map {
            SnapshotAssetValue(
                id = SnapshotAssetValueId(snapshot.id, it.assetClassCode),
                value = Money.normalize(it.value),
            )
        })
        snapshot.liabilityBalances.clear()
        snapshot.liabilityBalances.addAll(request.liabilities.map {
            SnapshotLiabilityBalance(
                id = SnapshotLiabilityBalanceId(snapshot.id, it.liabilityId),
                balance = Money.normalize(it.balance),
            )
        })

        snapshots.save(snapshot)
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
    fun latest(householdId: UUID): SnapshotDto? = snapshots.findLatest(householdId)?.let { toDto(it) }

    @Transactional(readOnly = true)
    fun asOf(householdId: UUID, date: LocalDate): SnapshotDto? =
        snapshots.findUpTo(householdId, date).firstOrNull()?.let { toDto(it) }

    @Transactional(readOnly = true)
    fun prefill(householdId: UUID): PrefillView {
        val previous = snapshots.findLatest(householdId)?.let { toDto(it) }
        val active = liabilities.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId).map { it.id }
        return PrefillView(previous, active)
    }

    @Transactional(readOnly = true)
    fun exportCsv(householdId: UUID): String {
        val all = snapshots.findAllOrdered(householdId)
        val liabilityNames = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)
            .associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append(Csv.row("date", "note", "kind", "key", "value"))
        for (s in all) {
            for (a in s.assetValues) {
                sb.append(Csv.row(s.snapshotDate, s.note, "asset", a.id.assetClassCode, Csv.decimal(a.value)))
            }
            for (l in s.liabilityBalances) {
                val name = liabilityNames[l.id.liabilityId] ?: l.id.liabilityId.toString()
                sb.append(Csv.row(s.snapshotDate, s.note, "liability", name, Csv.decimal(l.balance)))
            }
        }
        return sb.toString()
    }

    fun toDto(snapshot: Snapshot): SnapshotDto {
        val assets = snapshot.assetValues.map { AssetValueDto(it.id.assetClassCode, it.value) }
        val liabilities = snapshot.liabilityBalances.map { LiabilityBalanceDto(it.id.liabilityId, it.balance) }
        val totalAssets = assets.fold(BigDecimal.ZERO) { acc, a -> acc + a.value }
        val totalLiabilities = liabilities.fold(BigDecimal.ZERO) { acc, l -> acc + l.balance }
        return SnapshotDto(
            id = snapshot.id,
            snapshotDate = snapshot.snapshotDate,
            note = snapshot.note,
            totalAssets = Money.normalize(totalAssets),
            totalLiabilities = Money.normalize(totalLiabilities),
            netWorth = Money.normalize(totalAssets - totalLiabilities),
            assets = assets,
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
        return false
    }

    private fun changeExceeds(prev: BigDecimal, next: BigDecimal, threshold: BigDecimal): Boolean {
        if (prev.signum() == 0 && next.signum() == 0) return false
        if (prev.signum() == 0) return next.signum() != 0
        val ratio = (next - prev).abs().divide(prev.abs(), 6, RoundingMode.HALF_EVEN)
        return ratio > threshold
    }
}
