package com.sephilabs.sharedledger.fire.history

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.fire.ContributionMode
import com.sephilabs.sharedledger.fire.FireDefaults
import com.sephilabs.sharedledger.fire.FireReferenceReach
import com.sephilabs.sharedledger.fire.FireSectionCache
import com.sephilabs.sharedledger.fire.FireService
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.math.abs

@Service
class FireHistoryService(
    private val fire: FireService,
    private val snapshots: SnapshotRepository,
    private val cache: FireSectionCache,
) {

    @Transactional(readOnly = true)
    fun history(householdId: UUID): FireHistoryResponse =
        cache.getOrCompute(householdId, SECTION_HISTORY, "", fire.settingsDto(householdId)) { compute(householdId) }

    /** Attribution between today and the point dated `from`. */
    @Transactional(readOnly = true)
    fun attribution(householdId: UUID, from: LocalDate): FireAttribution {
        val series = history(householdId)
        val current = series.points.lastOrNull() ?: throw AppException.notFound("FIRE_HISTORY_POINT_NOT_FOUND")
        val start = series.points.firstOrNull { !it.current && it.date == from }
            ?: throw AppException.notFound("FIRE_HISTORY_POINT_NOT_FOUND")
        return attribute(householdId, start, current)
    }

    private fun compute(householdId: UUID): FireHistoryResponse {
        val today = LocalDate.now()
        val live = fire.referenceReachLive(householdId)
        val ordered = snapshots.findAllOrdered(householdId)
        val candidates = quarterPoints(ordered, today)
        val kept = candidates.takeLast(MAX_PAST_POINTS)
        val omissions = mutableMapOf<FireHistoryOmissionReason, Int>()
        if (candidates.size > kept.size) omissions[FireHistoryOmissionReason.beyond_point_limit] = candidates.size - kept.size

        val past = kept.mapNotNull { snapshot ->
            val reach = fire.referenceReachAt(householdId, snapshot, ordered.filter { !it.snapshotDate.isAfter(snapshot.snapshotDate) })
            val reason = omissionReason(reach)
            if (reason != null) {
                omissions.merge(reason, 1, Int::plus)
                null
            } else {
                point(reach, snapshot.snapshotDate, snapshot.snapshotDate, current = false)
            }
        }
        val latest = ordered.lastOrNull()
        val current = if (latest != null && live.target != null && live.coveragePercent != null) {
            point(live, today, latest.snapshotDate, current = true)
        } else {
            null
        }
        val points = if (current == null) emptyList() else past + current
        val default = current?.let { defaultStart(past, it) }
        return FireHistoryResponse(
            tier = live.tier,
            horizonYear = live.horizonYear,
            points = points,
            omitted = omissions.entries.sortedBy { it.key }.map { FireHistoryOmission(it.key, it.value) },
            manualContribution = live.contributionMode == ContributionMode.manual,
            manualSpending = !live.spendingDerived,
            attribution = if (current != null && default != null) attribute(householdId, default, current) else null,
        )
    }

    /** The last snapshot of each past quarter; the current quarter is represented by today's photo. */
    private fun quarterPoints(ordered: List<Snapshot>, today: LocalDate): List<Snapshot> =
        ordered
            .filter { !it.snapshotDate.isAfter(today) && quarterOf(it.snapshotDate) != quarterOf(today) }
            .groupBy { quarterOf(it.snapshotDate) }
            .values
            .map { it.last() }

    private fun quarterOf(date: LocalDate): Int = date.year * 4 + date.get(IsoFields.QUARTER_OF_YEAR)

    private fun omissionReason(reach: FireReferenceReach): FireHistoryOmissionReason? = when {
        reach.spendingDerived && reach.spendingMonthsAvailable < reach.spendingLookbackMonths ->
            FireHistoryOmissionReason.insufficient_spending_history
        reach.target == null || reach.coveragePercent == null -> FireHistoryOmissionReason.no_target
        else -> null
    }

    private fun point(reach: FireReferenceReach, date: LocalDate, snapshotDate: LocalDate, current: Boolean) =
        FireHistoryPoint(
            date = date,
            snapshotDate = snapshotDate,
            current = current,
            coveragePercent = reach.coveragePercent ?: 0.0,
            estimatedYear = reach.estimatedYear,
            probability = reach.probability,
            wealth = reach.wealth,
            target = reach.target ?: BigDecimal.ZERO,
        )

    /** The past point closest to a year before the current one (the earlier of two equally close). */
    private fun defaultStart(past: List<FireHistoryPoint>, current: FireHistoryPoint): FireHistoryPoint? {
        val aYearBefore = current.date.minusMonths(ATTRIBUTION_DEFAULT_MONTHS_BACK)
        return past.minWithOrNull(compareBy({ abs(ChronoUnit.DAYS.between(aYearBefore, it.date)) }, { it.date }))
    }

    private fun attribute(householdId: UUID, from: FireHistoryPoint, to: FireHistoryPoint): FireAttribution {
        val flows = fire.qualifyingFlows(householdId, from.snapshotDate, to.snapshotDate)
        val coverageStart = flows.firstMovementDate
        // Without recorded movements across the interval, contributions and the market cannot be told apart.
        val separable = flows.count > 0 && coverageStart != null &&
            !coverageStart.isAfter(from.snapshotDate.plusMonths(FireDefaults.MOVEMENT_COVERAGE_GAP_MONTHS))
        val net = flows.net.takeIf { separable }
        val parts = CoverageAttribution.split(
            CoverageAttribution.Endpoint(from.wealth, from.target),
            CoverageAttribution.Endpoint(to.wealth, to.target),
            net,
        )
        val contributed = parts.contributed
        val market = parts.market
        val shown = if (contributed != null && market != null) {
            val (c, m, n) = CoverageAttribution.roundToTenths(listOf(contributed, market, parts.need), parts.total)
            Shown(c, m, c + m, n)
        } else {
            val (w, n) = CoverageAttribution.roundToTenths(listOf(parts.wealth, parts.need), parts.total)
            Shown(null, null, w, n)
        }
        val wealthChange = to.wealth - from.wealth
        return FireAttribution(
            fromDate = from.date,
            toDate = to.date,
            merged = net == null,
            totalPoints = shown.wealth + shown.need,
            contributedPoints = shown.contributed,
            marketPoints = shown.market,
            wealthPoints = shown.wealth,
            needPoints = shown.need,
            exact = FireAttributionExact(parts.total, parts.contributed, parts.market, parts.wealth, parts.need),
            wealthFrom = from.wealth,
            wealthTo = to.wealth,
            targetFrom = from.target,
            targetTo = to.target,
            contributedAmount = net,
            marketAmount = net?.let { Money.normalize(wealthChange - it) },
        )
    }

    private class Shown(val contributed: BigDecimal?, val market: BigDecimal?, val wealth: BigDecimal, val need: BigDecimal)

    private companion object {
        const val SECTION_HISTORY = "history"
        /** At most one point per quarter for ten years, plus today's. */
        const val MAX_PAST_POINTS = 39
        const val ATTRIBUTION_DEFAULT_MONTHS_BACK = 12L
    }
}
