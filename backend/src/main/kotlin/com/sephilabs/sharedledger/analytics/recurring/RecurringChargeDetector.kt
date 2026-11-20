package com.sephilabs.sharedledger.analytics.recurring

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
    val anchorKey: String = normalizedKey,
    val memberKeys: Set<String> = setOf(normalizedKey),
    val searchQuery: String,
    val mergedVariants: List<String> = emptyList(),
    val bankDescription: String?,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    val action: RecurringAction = RecurringDetectionConstants.resolveRecurringAction(categoryCode, essential),
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
    val lastOccurrenceDate: LocalDate? = null,
    val typicalDayOfMonth: Int? = null,
    val matchedTxIds: Set<UUID> = emptySet(),
    val attributedEntries: List<String> = emptyList(),
    val multiPerMonth: Boolean = false,
    val observedMonths: Set<YearMonth> = emptySet(),
    val skippedCalendarMonths: Set<Int> = emptySet(),
)

/** Detects recurring charges over merged raw local payee variants across 24 months of history and merges them
 *  with active expense [RecurringTemplate]s without double-counting. Shared by Spending review and Forecast. */
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
        splitRules: List<PayeeVariantClusterer.SplitRule> = emptyList(),
        excludedPayeeMatcher: (categoryCode: String, memberKeys: Set<String>) -> Boolean = { _, _ -> false },
        recencyReferenceDate: LocalDate? = null,
        householdMinDate: LocalDate? = null,
        twoYearWindowEnd: YearMonth? = null,
    ): List<DetectedRecurringCharge> {
        val results = mutableListOf<DetectedRecurringCharge>()
        val coveredTemplateIds = mutableSetOf<UUID>()
        val coveredTemplateLabelsByCat = mutableMapOf<String, MutableList<String>>()
        // Anchor recency on non-template expenses (unless caller pins an explicit reference date) so
        // auto-materialized templates in unimported months never falsely hide active recurring charges.
        val latestNonTemplateExpenseDate = recencyReferenceDate
            ?: history24m.filter { it.recurringTemplateId == null }.maxOfOrNull { it.occurrenceDate }
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
                anchorKey = normKey,
                memberKeys = setOf(normKey),
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
                lastOccurrenceDate = matchingTx.lastOrNull()?.occurrenceDate,
                typicalDayOfMonth = tpl.startDate.dayOfMonth,
                matchedTxIds = matchingTx.map { it.id }.toSet(),
                observedMonths = matchingTx.map { YearMonth.from(it.occurrenceDate) }.toSet(),
            )
        }

        // 2. Merge name variants within each category (honouring user split rules) before detecting recurring charges.
        val candidates = history24m
            .filter { it.recurringTemplateId == null || it.recurringTemplateId !in coveredTemplateIds }
            .filter { !it.rawLabel.isNullOrBlank() && it.amount.signum() > 0 }

        val clusters = PayeeVariantClusterer.clusterByCategory(candidates, splitRules)
        for (cluster in clusters) {
            if (excludedPayeeMatcher(cluster.categoryCode, cluster.memberKeys)) continue

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
            if (txs.size < RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_YEARLY) continue

            val detected = analyzeCluster(cluster, latestNonTemplateExpenseDate) ?: continue
            results += detected
        }

        // 3. Gap-month same-amount attribution: a single unmatched transaction in the same category, within
        //    RECURRING_AMOUNT_TOLERANCE of a detected monthly charge, in a calendar month where that charge
        //    has no matched transaction belongs to that charge's footprint (excluded from discretionary avg).
        val attributed = attributeGapMonthTransactions(results, history24m, coveredTemplateIds)

        // 4. Conservative skipped-month mask: a detected monthly charge skips a calendar month only when
        //    absent in that same calendar month in BOTH of the last two years (>= 24 months of history).
        val endYm = twoYearWindowEnd ?: latestNonTemplateExpenseDate?.let(YearMonth::from)
        val withSkippedMask = applySkippedCalendarMonthsMask(attributed, householdMinDate, endYm)

        return withSkippedMask.sortedByDescending { it.annualCost }
    }

    private fun attributeGapMonthTransactions(
        charges: List<DetectedRecurringCharge>,
        history24m: List<ExpenseRecord>,
        coveredTemplateIds: Set<UUID>,
    ): List<DetectedRecurringCharge> {
        val detectedMonthlyIndices = charges.indices.filter {
            charges[it].source == RecurringSource.detected && charges[it].cadence == DetectedCadence.monthly
        }
        if (detectedMonthlyIndices.isEmpty()) return charges

        val alreadyMatched = charges.flatMapTo(mutableSetOf()) { it.matchedTxIds }
        // A payee key appearing in >= 3 distinct months in the same category is its own repeat/ended merchant
        // (e.g. a cancelled subscription), not a one-off mislabelled gap month of another charge.
        val distinctMonthsByCatAndKey = history24m
            .filter { !it.rawLabel.isNullOrBlank() }
            .groupBy { it.categoryCode to PayeeVariantClusterer.canonicalKey(it.rawLabel!!) }
            .mapValues { (_, list) -> list.map { YearMonth.from(it.occurrenceDate) }.toSet().size }

        val unmatchedByCatAndYm = history24m
            .filter {
                it.id !in alreadyMatched &&
                    (it.recurringTemplateId == null || it.recurringTemplateId !in coveredTemplateIds) &&
                    it.amount.signum() > 0 &&
                    (it.rawLabel.isNullOrBlank() ||
                        (distinctMonthsByCatAndKey[it.categoryCode to PayeeVariantClusterer.canonicalKey(it.rawLabel)] ?: 0) <
                        RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_MONTHLY)
            }
            .groupBy { it.categoryCode to YearMonth.from(it.occurrenceDate) }

        val mutable = charges.toMutableList()
        val extraMatchedIds = Array(charges.size) { mutableSetOf<UUID>() }
        val extraMonths = Array(charges.size) { mutableSetOf<YearMonth>() }
        val extraLabels = Array(charges.size) { linkedSetOf<String>() }
        val extraLatestDate = Array<LocalDate?>(charges.size) { charges[it].lastOccurrenceDate }

        for ((catAndYm, pool) in unmatchedByCatAndYm) {
            val (categoryCode, ym) = catAndYm
            val missingCharges = detectedMonthlyIndices.filter { idx ->
                val c = mutable[idx]
                c.categoryCode == categoryCode && ym !in c.observedMonths
            }
            if (missingCharges.isEmpty()) continue

            // Match each missing charge to its candidates within RECURRING_AMOUNT_TOLERANCE.
            val candidatesByCharge = missingCharges.associateWith { idx ->
                val c = mutable[idx]
                val tol = c.latestAmount.multiply(RecurringDetectionConstants.RECURRING_AMOUNT_TOLERANCE_PCT)
                    .max(RecurringDetectionConstants.RECURRING_AMOUNT_TOLERANCE_ABS)
                pool.filter { rec -> (rec.amount - c.latestAmount).abs() <= tol }
            }

            // Attribute ONLY when unambiguous: exactly 1 matching transaction for the charge, and no other
            // missing charge in the same category claims that same transaction.
            val chargeCountPerTx = mutableMapOf<UUID, Int>()
            for ((_, matches) in candidatesByCharge) {
                for (rec in matches) {
                    chargeCountPerTx[rec.id] = (chargeCountPerTx[rec.id] ?: 0) + 1
                }
            }

            for ((idx, matches) in candidatesByCharge) {
                if (matches.size != 1) continue
                val rec = matches[0]
                if (chargeCountPerTx[rec.id] != 1) continue
                extraMatchedIds[idx] += rec.id
                extraMonths[idx] += ym
                val lbl = rec.rawLabel?.trim()?.takeIf { it.isNotEmpty() } ?: rec.occurrenceDate.toString()
                extraLabels[idx] += lbl
                val prevLatest = extraLatestDate[idx]
                if (prevLatest == null || rec.occurrenceDate.isAfter(prevLatest)) {
                    extraLatestDate[idx] = rec.occurrenceDate
                }
            }
        }

        for (idx in detectedMonthlyIndices) {
            if (extraMatchedIds[idx].isEmpty()) continue
            val c = mutable[idx]
            mutable[idx] = c.copy(
                occurrences = c.occurrences + extraMatchedIds[idx].size,
                matchedTxIds = c.matchedTxIds + extraMatchedIds[idx],
                observedMonths = c.observedMonths + extraMonths[idx],
                attributedEntries = (c.attributedEntries + extraLabels[idx]).distinct(),
                lastOccurrenceDate = extraLatestDate[idx],
            )
        }
        return mutable
    }

    private fun applySkippedCalendarMonthsMask(
        charges: List<DetectedRecurringCharge>,
        householdMinDate: LocalDate?,
        twoYearWindowEnd: YearMonth?,
    ): List<DetectedRecurringCharge> {
        if (householdMinDate == null || twoYearWindowEnd == null) return charges
        val twoYearStart = twoYearWindowEnd.minusMonths(23)
        // Require at least 24 months of household history before skipping any calendar month.
        if (YearMonth.from(householdMinDate).isAfter(twoYearStart)) return charges

        val year1Months = (0L..11L).map { twoYearStart.plusMonths(it) }
        return charges.map { c ->
            if (c.source != RecurringSource.detected || c.cadence != DetectedCadence.monthly) return@map c
            val y1Active = year1Months.count { it in c.observedMonths }
            val y2Active = year1Months.count { it.plusYears(1) in c.observedMonths }
            // Must be active across both years (>= 8 months in each year) so a newer charge never skips months.
            if (y1Active < 8 || y2Active < 8) return@map c
            val skipped = year1Months
                .filter { ym1 -> ym1 !in c.observedMonths && ym1.plusYears(1) !in c.observedMonths }
                .map { it.monthValue }
                .toSet()
            if (skipped.size in 1..4) c.copy(skippedCalendarMonths = skipped) else c
        }
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

        // 1. Weekly cadence.
        if (medianGap != null &&
            medianGap in RecurringDetectionConstants.RECURRING_WEEKLY_GAP_DAYS &&
            txs.size >= RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_WEEKLY
        ) {
            val stability = maxOf(medianStability(amounts), stepStability(amounts))
            if (stability >= RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO) {
                cadence = DetectedCadence.weekly
                representativeAmount = txs.last().amount
                cadenceStable = gapRatio(positiveGaps, RecurringDetectionConstants.RECURRING_WEEKLY_GAP_DAYS) >=
                    RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO
            }
        }

        // 2. Multi-charge-per-month monthly payees.
        if (cadence == null && hasMultiChargeMonths && months.size >= RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_MONTHLY) {
            val monthGaps = months.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.atDay(15), b.atDay(15)).toInt() }
            val monthMedGap = medianInt(monthGaps)
            if (monthMedGap != null && monthMedGap in RecurringDetectionConstants.RECURRING_MONTHLY_GAP_DAYS) {
                val monthlyTotals = byMonth.values.map { list -> list.fold(BigDecimal.ZERO) { a, r -> a + r.amount } }
                val mStability = maxOf(medianStability(monthlyTotals), stepStability(monthlyTotals))
                if (mStability >= RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO) {
                    cadence = DetectedCadence.monthly
                    multiPerMonth = true
                    cadenceStable = gapRatio(monthGaps, RecurringDetectionConstants.RECURRING_MONTHLY_GAP_DAYS) >=
                        RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO
                    representativeAmount = medianDecimal(monthlyTotals.takeLast(3))
                }
            }
        }

        // 3. Single-charge monthly, quarterly, or yearly cadence.
        if (cadence == null && medianGap != null) {
            val stability = maxOf(medianStability(amounts), stepStability(amounts))
            when {
                medianGap in RecurringDetectionConstants.RECURRING_MONTHLY_GAP_DAYS &&
                    months.size >= RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_MONTHLY &&
                    stability >= RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO -> {
                    cadence = DetectedCadence.monthly
                    representativeAmount = txs.last().amount
                    cadenceStable = gapRatio(positiveGaps, RecurringDetectionConstants.RECURRING_MONTHLY_GAP_DAYS) >=
                        RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO
                }
                medianGap in RecurringDetectionConstants.RECURRING_QUARTERLY_GAP_DAYS &&
                    txs.size >= RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_QUARTERLY &&
                    stability >= RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO -> {
                    cadence = DetectedCadence.quarterly
                    representativeAmount = txs.last().amount
                    cadenceStable = gapRatio(positiveGaps, RecurringDetectionConstants.RECURRING_QUARTERLY_GAP_DAYS) >=
                        RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO
                }
                medianGap in RecurringDetectionConstants.RECURRING_YEARLY_GAP_DAYS &&
                    txs.size >= RecurringDetectionConstants.RECURRING_MIN_OCCURRENCES_YEARLY &&
                    stepStability(amounts) >= RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO &&
                    txs.last().amount >= RecurringDetectionConstants.RECURRING_YEARLY_MIN_AMOUNT -> {
                    cadence = DetectedCadence.yearly
                    representativeAmount = txs.last().amount
                    cadenceStable = gapRatio(positiveGaps, RecurringDetectionConstants.RECURRING_YEARLY_GAP_DAYS) >=
                        RecurringDetectionConstants.RECURRING_MIN_STABILITY_RATIO
                }
            }
        }

        val resolvedCadence = cadence ?: return null
        val latest = txs.last()

        if (latestHouseholdExpenseDate != null && isSeriesEnded(resolvedCadence, latest.occurrenceDate, latestHouseholdExpenseDate)) {
            return null
        }

        val normLatest = Money.normalize(representativeAmount)
        val (monthly, annual) = normalizeCost(resolvedCadence, normLatest)
        if (annual < RecurringDetectionConstants.RECURRING_MIN_ANNUAL_COST) return null

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
            anchorKey = cluster.anchorKey,
            memberKeys = cluster.memberKeys,
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
            lastOccurrenceDate = latest.occurrenceDate,
            typicalDayOfMonth = medianInt(dates.map { it.dayOfMonth }),
            matchedTxIds = txs.map { it.id }.toSet(),
            multiPerMonth = multiPerMonth,
            observedMonths = byMonth.keys.toSet(),
        )
    }

    private fun isSeriesEnded(cadence: DetectedCadence, lastDate: LocalDate, householdLatestDate: LocalDate): Boolean {
        val gapDays = ChronoUnit.DAYS.between(lastDate, householdLatestDate)
        return when (cadence) {
            DetectedCadence.weekly -> gapDays > RecurringDetectionConstants.RECURRING_MAX_STALE_DAYS_WEEKLY
            DetectedCadence.monthly -> gapDays > RecurringDetectionConstants.RECURRING_MAX_STALE_DAYS_MONTHLY
            DetectedCadence.quarterly -> gapDays > RecurringDetectionConstants.RECURRING_MAX_STALE_DAYS_QUARTERLY
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
        if (multiPerMonth && cadence == DetectedCadence.monthly) {
            val latestYm = YearMonth.from(latestDate)
            val latestCount = byMonth[latestYm]?.size ?: 1
            val targetYm = latestYm.minusYears(1)
            val candidateYms = listOf(targetYm, targetYm.minusMonths(1), targetYm.plusMonths(1))
            val priorYm = candidateYms.firstOrNull { it in byMonth } ?: return null to false
            val priorList = byMonth[priorYm].orEmpty()
            val priorSum = Money.normalize(priorList.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
            val countStable = priorList.size == latestCount
            return priorSum to countStable
        }
        val prior = findPriorYearSingle(txs, latestDate)
        return prior to (prior != null)
    }

    private fun findPriorYearSingle(txs: List<ExpenseRecord>, latestDate: LocalDate?): BigDecimal? {
        if (latestDate == null) return null
        val target = latestDate.minusYears(1)
        val prior = txs
            .filter { abs(ChronoUnit.DAYS.between(it.occurrenceDate, target)) <= RecurringDetectionConstants.RECURRING_YOY_WINDOW_DAYS }
            .minByOrNull { abs(ChronoUnit.DAYS.between(it.occurrenceDate, target)) }
        return prior?.amount?.let(Money::normalize)
    }

    private fun yoyDelta(latest: BigDecimal, prior: BigDecimal?): Pair<BigDecimal?, Double?> {
        if (prior == null || prior.signum() <= 0) return null to null
        val absDiff = Money.normalize(latest - prior)
        val pct = absDiff.divide(prior, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
        return absDiff to pct
    }

    private fun classifyDelta(yoyAbs: BigDecimal?, cadenceStable: Boolean, countStable: Boolean): YoyChangeKind? {
        if (yoyAbs == null || yoyAbs.signum() == 0) return null
        if (!cadenceStable || !countStable) return YoyChangeKind.changed
        return if (yoyAbs.signum() > 0) YoyChangeKind.price_increase else YoyChangeKind.price_decrease
    }

    private fun gapRatio(gaps: List<Int>, range: IntRange): Double {
        if (gaps.isEmpty()) return 0.0
        return gaps.count { it in range }.toDouble() / gaps.size.toDouble()
    }

    private fun medianStability(amounts: List<BigDecimal>): Double {
        if (amounts.isEmpty()) return 0.0
        val med = medianDecimal(amounts)
        val tol = med.multiply(RecurringDetectionConstants.RECURRING_AMOUNT_TOLERANCE_PCT)
            .max(RecurringDetectionConstants.RECURRING_AMOUNT_TOLERANCE_ABS)
        val within = amounts.count { (it - med).abs() <= tol }
        return within.toDouble() / amounts.size.toDouble()
    }

    private fun stepStability(amounts: List<BigDecimal>): Double {
        if (amounts.size < 2) return 0.0
        var ok = 0
        for (i in 0 until amounts.size - 1) {
            val prev = amounts[i]
            val curr = amounts[i + 1]
            val base = prev.max(curr)
            val tol = base.multiply(RecurringDetectionConstants.RECURRING_AMOUNT_TOLERANCE_PCT)
                .max(RecurringDetectionConstants.RECURRING_AMOUNT_TOLERANCE_ABS)
            if ((curr - prev).abs() <= tol) ok++
        }
        return ok.toDouble() / (amounts.size - 1).toDouble()
    }

    private fun medianInt(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun medianDecimal(values: List<BigDecimal>): BigDecimal {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]).divide(BigDecimal.valueOf(2L), 2, RoundingMode.HALF_EVEN)
        }
    }
}
