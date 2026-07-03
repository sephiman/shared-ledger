package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.catalog.AssetClassRepository
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.portfolio.ASSET_CLASS_TO_SNAPSHOT_CODE
import com.sephilabs.sharedledger.portfolio.PortfolioValuationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Service
class AutoSnapshotService(
    private val settings: AutoSnapshotSettingsRepository,
    private val snapshots: SnapshotRepository,
    private val snapshotService: SnapshotService,
    private val assetClasses: AssetClassRepository,
    private val liabilities: LiabilityRepository,
    private val portfolioValuation: PortfolioValuationService,
    private val members: HouseholdMemberRepository,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(AutoSnapshotService::class.java)

    @Transactional
    fun getOrCreate(householdId: UUID): AutoSnapshotSettings =
        settings.findById(householdId).orElseGet {
            settings.save(AutoSnapshotSettings(householdId = householdId))
        }

    @Transactional
    fun update(householdId: UUID, enabled: Boolean, frequency: SnapshotFrequency, by: User): AutoSnapshotSettings {
        val s = getOrCreate(householdId)
        s.enabled = enabled
        s.frequency = frequency
        s.updatedByUserId = by.id
        s.updatedAt = Instant.now()
        return s
    }

    /** Scheduler entry point: create today's snapshot for every household that's due. */
    fun runForAll(today: LocalDate): Int {
        val enabled = settings.findAllByEnabledTrue()
        log.info("Auto-snapshot job running for {} enabled households", enabled.size)
        var created = 0
        for (s in enabled) {
            try {
                if (runForHousehold(s.householdId, s.frequency, today)) created++
            } catch (ex: Exception) {
                log.error("Auto-snapshot failed for household {}", s.householdId, ex)
            }
        }
        return created
    }

    /**
     * Creates the snapshot for [today] if the frequency makes it due and none exists yet.
     * Returns true when a snapshot was created.
     */
    fun runForHousehold(householdId: UUID, frequency: SnapshotFrequency, today: LocalDate): Boolean {
        if (!isDue(frequency, today)) return false
        // One snapshot per date: never duplicate a manual or earlier-run snapshot, never edit one.
        if (snapshots.existsByHouseholdIdAndSnapshotDate(householdId, today)) return false
        val ownerId = ownerOf(householdId) ?: run {
            log.warn("Auto-snapshot skipped for household {}: no owner found", householdId)
            return false
        }
        val request = buildRequest(householdId, today)
        snapshotService.createScheduled(householdId, request, ownerId)
        log.info("Auto-snapshot created for household {} on {}", householdId, today)
        return true
    }

    private fun isDue(frequency: SnapshotFrequency, today: LocalDate): Boolean = when (frequency) {
        SnapshotFrequency.daily -> true
        SnapshotFrequency.weekly -> today.dayOfWeek == props.autoSnapshot.weeklyDay
        SnapshotFrequency.monthly ->
            today.dayOfMonth == props.autoSnapshot.monthlyDay.coerceAtMost(today.lengthOfMonth())
    }

    @Transactional(readOnly = true)
    fun buildRequest(householdId: UUID, date: LocalDate): SnapshotRequest {
        val expectedClasses = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }
        val activeLiabilities = liabilities.findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId)

        // Fresh portfolio values, but ONLY for classes the portfolio actually priced —
        // an unpriced class (e.g. a fund with no provider) must never overwrite a real
        // carried-over balance with a guessed zero.
        val valuation = portfolioValuation.valuationAt(householdId, date)
        val pricedClasses = valuation.holdings
            .filter { it.unitPrice != null }
            .mapNotNull { ASSET_CLASS_TO_SNAPSHOT_CODE[it.assetClass] }
            .toSet()
        val computedByClass = valuation.byClass.filterKeys { it in pricedClasses }

        // "Previous" = latest snapshot strictly before this date (by date, then creation).
        val previous = snapshots.findUpTo(householdId, date.minusDays(1)).firstOrNull()
        val previousAssets = previous?.assetValues?.associate { it.id.assetClassCode to it.value } ?: emptyMap()
        val previousLiabilities = previous?.liabilityBalances?.associate { it.id.liabilityId to it.balance } ?: emptyMap()

        val assets = expectedClasses.map { code ->
            val computed = computedByClass[code]
            when {
                computed != null -> AssetValueInput(code, computed, VALUE_SOURCE_COMPUTED)
                previous != null && previousAssets.containsKey(code) ->
                    AssetValueInput(code, previousAssets.getValue(code), VALUE_SOURCE_CARRIED_OVER)
                // First-ever snapshot (or class absent before): leave the manual class empty.
                else -> AssetValueInput(code, BigDecimal.ZERO, VALUE_SOURCE_OVERRIDDEN)
            }
        }
        val liabilityInputs = activeLiabilities.map { liability ->
            LiabilityBalanceInput(liability.id, previousLiabilities[liability.id] ?: BigDecimal.ZERO)
        }

        return SnapshotRequest(
            snapshotDate = date,
            note = null,
            assets = assets,
            liabilities = liabilityInputs,
            // The job runs unattended; a large market swing must not block it.
            confirmLargeChanges = true,
        )
    }

    private fun ownerOf(householdId: UUID): UUID? =
        members.findAllByIdHouseholdId(householdId)
            .firstOrNull { it.role == HouseholdRole.owner }
            ?.id?.userId
}
