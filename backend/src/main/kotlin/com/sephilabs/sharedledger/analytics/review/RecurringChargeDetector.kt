package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

enum class DetectedCadence { weekly, monthly, quarterly, yearly }

enum class RecurringSource { template, detected }

enum class YoyChangeKind { price_increase, price_decrease, changed }

data class ExpenseRecord(
    val id: UUID,
    val occurrenceDate: LocalDate,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    val amount: BigDecimal,
    val rawLabel: String?,
    val bankDescription: String?,
    val recurringTemplateId: UUID?,
)

data class DetectedRecurringCharge(
    val merchant: String,
    val normalizedKey: String,
    val searchQuery: String,
    val mergedVariants: List<String> = emptyList(),
    val bankDescription: String?,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    val action: RecurringAction = SpendingReviewConstants.resolveRecurringAction(categoryCode, essential),
    val cadence: DetectedCadence,
    val source: RecurringSource,
    val occurrences: Int,
    val latestAmount: BigDecimal,
    val monthlyCost: BigDecimal,
    val annualCost: BigDecimal,
    val priorYearAmount: BigDecimal?,
    val yoyDeltaAbs: BigDecimal?,
    val yoyDeltaPct: Double?,
    val yoyChangeKind: YoyChangeKind?,
    val minObservedAmount: BigDecimal,
    val maxObservedAmount: BigDecimal,
)

/** Detects recurring charges over merged raw local payee variants across 24 months of history and merges them
 *  with active expense [RecurringTemplate]s without double-counting. */
object RecurringChargeDetector {

    private val TWELVE = BigDecimal.valueOf(12L)
    private val FIFTY_TWO = BigDecimal.valueOf(52L)
    private val FOUR = BigDecimal.valueOf(4L)
    private val THREE = BigDecimal.valueOf(3L)

    fun detect(
        history24m: List<ExpenseRecord>,
        activeTemplates: List<RecurringTemplate>,
        categoryMeta: Map<String, Pair<String?, Boolean>>,
        trailing12Start: LocalDate,
    ): List<DetectedRecurringCharge> {
        val results = mutableListOf<DetectedRecurringCharge>()
        val coveredTemplateIds = mutableSetOf<UUID>()
        val coveredTemplateLabelsByCat = mutableMapOf<String, MutableList<String>>()
        // Anchor recency on non-template expenses so auto-materialized templates during unimported months
        // never falsely hide active recurring charges, while truly cancelled charges are still excluded.
        val latestNonTemplateExpenseDate = history24m
            .filter { it.recurringTemplateId == null }
            .maxOfOrNull { it.occurrenceDate }
            ?: history24m.maxOfOrNull { it.occurrenceDate }

        // 1. Active expense recurring templates first.
        for (tpl in activeTemplates) {
            val label = tpl.description?.trim()?.takeIf { it.isNotEmpty() } ?: tpl.categoryCode
            val normKey = PayeeVariantClusterer.canonicalKey(label)
            val meta = categoryMeta[tpl.categoryCode]
            val cadence = when (tpl.cadence) {
                Cadence.weekly -> DetectedCadence.weekly
                Cadence.monthly -> DetectedCadence.monthly
                Cadence.yearly -> DetectedCadence.yearly
            }
            val matchingTx = history24m.filter { it.recurringTemplateId == tpl.id }
                .sortedBy { it.occurrenceDate }
            val latestAmount = Money.normalize(tpl.amount)
            val (monthly, annual) = normalizeCost(cadence, latestAmount)
            val priorYear = findPriorYearSingle(matchingTx, matchingTx.lastOrNull()?.occurrenceDate)
            val (yoyAbs, yoyPct) = yoyDelta(latestAmount, priorYear)
            val yoyKind = classifyDelta(yoyAbs, cadenceStable = true, countStable = true)
            val observedAmounts = (matchingTx.map { it.amount } + latestAmount)

            coveredTemplateIds += tpl.id
            coveredTemplateLabelsByCat.getOrPut(tpl.categoryCode) { mutableListOf() }.add(label)
            results += DetectedRecurringCharge(
                merchant = label,
                normalizedKey = normKey,
                searchQuery = label,
                bankDescription = matchingTx.lastOrNull()?.bankDescription,
                categoryCode = tpl.categoryCode,
                groupCode = meta?.first,
                essential = meta?.second ?: false,
                cadence = cadence,
                source = RecurringSource.template,
                occurrences = matchingTx.size.coerceAtLeast(1),
                latestAmount = latestAmount,
                monthlyCost = monthly,
                annualCost = annual,
                priorYearAmount = priorYear,
                yoyDeltaAbs = yoyAbs,
                yoyDeltaPct = yoyPct,
                yoyChangeKind = yoyKind,
                minObservedAmount = Money.normalize(observedAmounts.min()),
                maxObservedAmount = Money.normalize(observedAmounts.max()),
            )
        }

        // 2. Merge name variants within each category before detecting history-based recurring charges.
        val candidates = history24m
            .filter { it.recurringTemplateId == null || it.recurringTemplateId !in coveredTemplateIds }
            .filter { !it.rawLabel.isNullOrBlank() && it.amount.signum() > 0 }

        val clusters = PayeeVariantClusterer.clusterByCategory(candidates)
        for (cluster in clusters) {
            val tplLabels = coveredTemplateLabelsByCat[cluster.categoryCode].orEmpty()
            if (tplLabels.any { tpl ->
                    PayeeVariantClusterer.canonicalKey(tpl) in cluster.memberKeys ||
                        PayeeVariantClusterer.areVariants(tpl, cluster.canonicalMerchant)
                }
            ) {
                continue
            }

            val txs = cluster.records
            if (txs.last().occurrenceDate.isBefore(trailing12Start)) continue
            if (txs.size < SpendingReviewConstants.RECURRING_MIN_OCCURRENCES_YEARLY) continue

            val detected = analyzeCluster(cluster, latestNonTemplateExpenseDate) ?: continue
            results += detected
        }

        return results.sortedByDescending { it.annualCost }
    }

    private fun analyzeCluster(
        cluster: PayeeVariantClusterer.PayeeCluster,
        latestHouseholdExpenseDate: LocalDate?,
    ): DetectedRecurringCharge? {
        val txs = cluster.records
        val dates = txs.map { it.occurrenceDate }
        val amounts = txs.map { it.amount }
        val byMonth = txs.groupBy { YearMonth.from(it.occurrenceDate) }.toSortedMap()
        val months = byMonth.keys.toList()
        val chargesPerMonth = byMonth.values.map { it.size }
        val medianChargesPerMonth = medianInt(chargesPerMonth) ?: 1
        val hasMultiChargeMonths = medianChargesPerMonth > 1 || chargesPerMonth.count { it > 1 } >= 2

        val positiveGaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.filter { it > 0 }
        val medianGap = medianInt(positiveGaps)

        var cadence: DetectedCadence? = null
        var representativeAmount: BigDecimal = txs.last().amount
        var multiPerMonth = false
        var cadenceStable = false

        // 1. Weekly cadence (distinct from multi-charge-per-month when gaps are consistently ~7 days).
        if (medianGap != null &&
            medianGap in SpendingReviewConstants.RECURRING_WEEKLY_GAP_DAYS &&
            txs.size >= SpendingReviewConstants.RECURRING_MIN_OCCURRENCES_WEEKLY
        ) {
            val stability = maxOf(medianStability(amounts), stepStability(amounts))
            if (stability >= SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO) {
                cadence = DetectedCadence.weekly
                representativeAmount = txs.last().amount
                cadenceStable = gapRatio(positiveGaps, SpendingReviewConstants.RECURRING_WEEKLY_GAP_DAYS) >=
                    SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO
            }
        }

        // 2. Multi-charge-per-month monthly payees (e.g., two insurance policies or multiple telecom lines).
        // Evaluated BEFORE single-charge monthly so monthly totals and charge counts are used consistently.
        if (cadence == null && hasMultiChargeMonths && months.size >= SpendingReviewConstants.RECURRING_MIN_OCCURRENCES_MONTHLY) {
            val monthGaps = months.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.atDay(15), b.atDay(15)).toInt() }
            val monthMedGap = medianInt(monthGaps)
            if (monthMedGap != null && monthMedGap in SpendingReviewConstants.RECURRING_MONTHLY_GAP_DAYS) {
                val monthlyTotals = byMonth.values.map { list -> list.fold(BigDecimal.ZERO) { a, r -> a + r.amount } }
                val mStability = maxOf(medianStability(monthlyTotals), stepStability(monthlyTotals))
                if (mStability >= SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO) {
                    cadence = DetectedCadence.monthly
                    multiPerMonth = true
                    cadenceStable = gapRatio(monthGaps, SpendingReviewConstants.RECURRING_MONTHLY_GAP_DAYS) >=
                        SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO
                    representativeAmount = medianDecimal(monthlyTotals.takeLast(3))
                }
            }
        }

        // 3. Single-charge monthly, quarterly, or yearly cadence.
        if (cadence == null && medianGap != null) {
            val stability = maxOf(medianStability(amounts), stepStability(amounts))
            when {
                medianGap in SpendingReviewConstants.RECURRING_MONTHLY_GAP_DAYS &&
                    months.size >= SpendingReviewConstants.RECURRING_MIN_OCCURRENCES_MONTHLY &&
                    stability >= SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO -> {
                    cadence = DetectedCadence.monthly
                    representativeAmount = txs.last().amount
                    cadenceStable = gapRatio(positiveGaps, SpendingReviewConstants.RECURRING_MONTHLY_GAP_DAYS) >=
                        SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO
                }
                medianGap in SpendingReviewConstants.RECURRING_QUARTERLY_GAP_DAYS &&
                    txs.size >= SpendingReviewConstants.RECURRING_MIN_OCCURRENCES_QUARTERLY &&
                    stability >= SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO -> {
                    cadence = DetectedCadence.quarterly
                    representativeAmount = txs.last().amount
                    cadenceStable = gapRatio(positiveGaps, SpendingReviewConstants.RECURRING_QUARTERLY_GAP_DAYS) >=
                        SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO
                }
                // Yearly cadence requires direct pairwise amount match (stepStability, not midpoint-doubled median)
                // plus a minimum per-charge amount so two unrelated purchases a year apart are never a subscription.
                medianGap in SpendingReviewConstants.RECURRING_YEARLY_GAP_DAYS &&
                    txs.size >= SpendingReviewConstants.RECURRING_MIN_OCCURRENCES_YEARLY &&
                    stepStability(amounts) >= SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO &&
                    txs.last().amount >= SpendingReviewConstants.RECURRING_YEARLY_MIN_AMOUNT -> {
                    cadence = DetectedCadence.yearly
                    representativeAmount = txs.last().amount
                    cadenceStable = gapRatio(positiveGaps, SpendingReviewConstants.RECURRING_YEARLY_GAP_DAYS) >=
                        SpendingReviewConstants.RECURRING_MIN_STABILITY_RATIO
                }
            }
        }

        val resolvedCadence = cadence ?: return null
        val latest = txs.last()

        // Drop clearly ended high-frequency series relative to the household's latest recorded expense.
        if (latestHouseholdExpenseDate != null && isSeriesEnded(resolvedCadence, latest.occurrenceDate, latestHouseholdExpenseDate)) {
            return null
        }

        val normLatest = Money.normalize(representativeAmount)
        val (monthly, annual) = normalizeCost(resolvedCadence, normLatest)
        if (annual < SpendingReviewConstants.RECURRING_MIN_ANNUAL_COST) return null

        val (priorYear, countStable) = computePriorYear(
            txs = txs,
            byMonth = byMonth,
            latestDate = latest.occurrenceDate,
            cadence = resolvedCadence,
            multiPerMonth = multiPerMonth,
        )
        val (yoyAbs, yoyPct) = yoyDelta(normLatest, priorYear)
        val yoyKind = classifyDelta(yoyAbs, cadenceStable = cadenceStable, countStable = countStable)

        return DetectedRecurringCharge(
            merchant = cluster.canonicalMerchant,
            normalizedKey = cluster.canonicalKey,
            searchQuery = cluster.searchQuery,
            mergedVariants = cluster.mergedVariants,
            bankDescription = txs.lastOrNull { !it.bankDescription.isNullOrBlank() }?.bankDescription,
            categoryCode = latest.categoryCode,
            groupCode = latest.groupCode,
            essential = latest.essential,
            cadence = resolvedCadence,
            source = RecurringSource.detected,
            occurrences = txs.size,
            latestAmount = normLatest,
            monthlyCost = monthly,
            annualCost = annual,
            priorYearAmount = priorYear,
            yoyDeltaAbs = yoyAbs,
            yoyDeltaPct = yoyPct,
            yoyChangeKind = yoyKind,
            minObservedAmount = Money.normalize(amounts.min()),
            maxObservedAmount = Money.normalize(amounts.max()),
        )
    }

    private fun isSeriesEnded(cadence: DetectedCadence, lastDate: LocalDate, householdLatestDate: LocalDate): Boolean {
        val gapDays = ChronoUnit.DAYS.between(lastDate, householdLatestDate)
        return when (cadence) {
            DetectedCadence.weekly -> gapDays > SpendingReviewConstants.RECURRING_MAX_STALE_DAYS_WEEKLY
            DetectedCadence.monthly -> gapDays > SpendingReviewConstants.RECURRING_MAX_STALE_DAYS_MONTHLY
            DetectedCadence.quarterly -> gapDays > SpendingReviewConstants.RECURRING_MAX_STALE_DAYS_QUARTERLY
            DetectedCadence.yearly -> false
        }
    }

    private fun normalizeCost(cadence: DetectedCadence, perCharge: BigDecimal): Pair<BigDecimal, BigDecimal> = when (cadence) {
        DetectedCadence.weekly -> {
            val annual = Money.normalize(perCharge.multiply(FIFTY_TWO))
            val monthly = annual.divide(TWELVE, 2, RoundingMode.HALF_EVEN)
            monthly to annual
        }
        DetectedCadence.monthly -> {
            val monthly = Money.normalize(perCharge)
            val annual = Money.normalize(monthly.multiply(TWELVE))
            monthly to annual
        }
        DetectedCadence.quarterly -> {
            val annual = Money.normalize(perCharge.multiply(FOUR))
            val monthly = perCharge.divide(THREE, 2, RoundingMode.HALF_EVEN)
            monthly to annual
        }
        DetectedCadence.yearly -> {
            val annual = Money.normalize(perCharge)
            val monthly = annual.divide(TWELVE, 2, RoundingMode.HALF_EVEN)
            monthly to annual
        }
    }

    private fun computePriorYear(
        txs: List<ExpenseRecord>,
        byMonth: Map<YearMonth, List<ExpenseRecord>>,
        latestDate: LocalDate,
        cadence: DetectedCadence,
        multiPerMonth: Boolean,
    ): Pair<BigDecimal?, Boolean> {
        if (cadence == DetectedCadence.monthly) {
            val latestYm = YearMonth.from(latestDate)
            val targetYm = latestYm.minusYears(1)
            val priorYm = listOf(targetYm, targetYm.minusMonths(1), targetYm.plusMonths(1))
                .firstOrNull { it in byMonth }
                ?: return null to true

            val recentMonths = byMonth.entries.toList().takeLast(3)
            val priorWindowMonths = listOf(priorYm.minusMonths(1), priorYm, priorYm.plusMonths(1))
                .mapNotNull { byMonth[it] }

            val recentTypicalCount = medianInt(recentMonths.map { it.value.size }) ?: 1
            val priorTypicalCount = medianInt(priorWindowMonths.map { it.size }) ?: 1
            val latestMonthList = byMonth[latestYm].orEmpty()
            val priorMonthList = byMonth[priorYm].orEmpty()
            val anyMulti = multiPerMonth || latestMonthList.size > 1 || priorMonthList.size > 1 ||
                recentTypicalCount > 1 || priorTypicalCount > 1

            val priorAmount = if (anyMulti) {
                Money.normalize(priorMonthList.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
            } else {
                findPriorYearSingle(txs, latestDate)
            }
            val countStable = recentTypicalCount == priorTypicalCount &&
                latestMonthList.size == recentTypicalCount &&
                priorMonthList.size == priorTypicalCount
            return priorAmount to countStable
        }

        val priorSingle = findPriorYearSingle(txs, latestDate)
        return priorSingle to true
    }

    private fun findPriorYearSingle(txs: List<ExpenseRecord>, latestDate: LocalDate?): BigDecimal? {
        if (latestDate == null || txs.isEmpty()) return null
        val target = latestDate.minusYears(1)
        val matches = txs.filter {
            abs(ChronoUnit.DAYS.between(target, it.occurrenceDate)) <= SpendingReviewConstants.RECURRING_YOY_WINDOW_DAYS
        }
        if (matches.isEmpty()) return null
        val closest = matches.minBy { abs(ChronoUnit.DAYS.between(target, it.occurrenceDate)) }
        return Money.normalize(closest.amount)
    }

    private fun yoyDelta(current: BigDecimal, prior: BigDecimal?): Pair<BigDecimal?, Double?> {
        if (prior == null || prior.signum() <= 0) return null to null
        val delta = Money.normalize(current - prior)
        val pct = delta.divide(prior, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
        return delta to pct
    }

    private fun classifyDelta(delta: BigDecimal?, cadenceStable: Boolean, countStable: Boolean): YoyChangeKind? {
        if (delta == null || delta.signum() == 0) return null
        if (!cadenceStable || !countStable) return YoyChangeKind.changed
        return if (delta.signum() > 0) YoyChangeKind.price_increase else YoyChangeKind.price_decrease
    }

    private fun gapRatio(gaps: List<Int>, expected: IntRange): Double =
        if (gaps.isEmpty()) 0.0 else gaps.count { it in expected }.toDouble() / gaps.size

    private fun withinTolerance(value: BigDecimal, baseline: BigDecimal): Boolean {
        val tol = baseline.abs().multiply(SpendingReviewConstants.RECURRING_AMOUNT_TOLERANCE_PCT)
            .max(SpendingReviewConstants.RECURRING_AMOUNT_TOLERANCE_ABS)
        return (value - baseline).abs() <= tol
    }

    private fun medianStability(values: List<BigDecimal>): Double {
        if (values.isEmpty()) return 0.0
        val med = medianDecimal(values)
        return values.count { withinTolerance(it, med) }.toDouble() / values.size
    }

    private fun stepStability(values: List<BigDecimal>): Double {
        if (values.size < 2) return 1.0
        var ok = 0
        for (i in 1 until values.size) {
            if (withinTolerance(values[i], values[i - 1])) ok++
        }
        return ok.toDouble() / (values.size - 1)
    }

    private fun medianDecimal(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]).divide(BigDecimal.valueOf(2L), 2, RoundingMode.HALF_EVEN)
    }

    private fun medianInt(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
