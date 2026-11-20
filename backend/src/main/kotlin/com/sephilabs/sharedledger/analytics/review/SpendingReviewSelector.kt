package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.MoneyFlowProjectionResponse
import com.sephilabs.sharedledger.analytics.MonthDashboardResponse
import com.sephilabs.sharedledger.analytics.TopMoversResponse
import com.sephilabs.sharedledger.analytics.TrailingResponse
import com.sephilabs.sharedledger.analytics.question.AdjustmentKind
import com.sephilabs.sharedledger.analytics.question.ProjectionAdjustment
import com.sephilabs.sharedledger.analytics.question.ProjectionScenario
import com.sephilabs.sharedledger.analytics.question.ProjectionScenarioDto
import com.sephilabs.sharedledger.analytics.question.ScopeDto
import com.sephilabs.sharedledger.analytics.question.ScopeRef
import com.sephilabs.sharedledger.analytics.question.ScopeType
import com.sephilabs.sharedledger.budget.MonthSummaryResponse
import com.sephilabs.sharedledger.budget.MonthSummaryRow
import com.sephilabs.sharedledger.catalog.CategoryView
import com.sephilabs.sharedledger.common.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

enum class ReviewMode { FULL, SAVINGS_ONLY }

enum class FindingSection { savings, whatChanged, budgets }

enum class FindingKind {
    cancellable_recurring,
    avoidable_fee,
    negotiable_price_increase,
    top_merchant,
    habitual_uneven,
    period_increase,
    non_actionable_price_change,
    zero_median_one_off,
    budget_overrun,
}

enum class FigureKind { MONEY, WHOLE_MONEY, PERCENT, COUNT, MULTIPLE }

data class FigureSlot(
    val key: String,
    val role: String,
    val kind: FigureKind,
    val value: BigDecimal,
)

enum class SummaryDiagnosis {
    shortfall_from_income,
    shortfall_from_spending,
    shortfall_from_both,
    on_track_or_above,
}

data class SelectedSummaryFacts(
    val periodYear: Int,
    val periodMonth: Int,
    val reviewKind: String,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val expensesDirection: String, // "below" | "above" | "at"
    val incomeDirection: String, // "below" | "above" | "at"
    val savingsDirection: String, // "below" | "above" | "at"
    val diagnosis: SummaryDiagnosis,
    val diagnosisDetail: String,
    val hasDeficit: Boolean,
    val hasProjectedDeficit: Boolean,
    val figures: List<FigureSlot>,
)

data class SelectedFinding(
    val id: String,
    val section: FindingSection,
    val kind: FindingKind,
    val candidateId: String?,
    val payloadSubject: String,
    val localSubject: String?,
    val anonymized: Boolean,
    val mergedVariants: List<String>,
    val categoryCode: String?,
    val groupCode: String?,
    val action: RecurringAction?,
    val severity: String, // "opportunity" | "warning" | "critical"
    val changeKind: String?, // "drift" | "one_off" | "price_increase" | "drop"
    val potentialMonthlySaving: BigDecimal?,
    val potentialAnnualSaving: BigDecimal?,
    val figures: List<FigureSlot>,
    val context: Map<String, String>,
    val txFilter: TransactionLinkFilterDto?,
    val explorerLink: ExplorerLinkDto?,
    val simulationScenario: ProjectionScenario?,
)

data class SelectedReview(
    val mode: ReviewMode,
    val currency: String,
    val period: SpendingReviewPeriodDto,
    val summaryFacts: SelectedSummaryFacts?,
    val savings: List<SelectedFinding>,
    val whatChanged: List<SelectedFinding>,
    val budgets: List<SelectedFinding>,
    val savingsOmitted: Int,
    val whatChangedOmitted: Int,
    val budgetsOmitted: Int,
    val underlyingMovers: List<UnderlyingMoverDto>,
    val underlyingBudgetOverruns: List<UnderlyingBudgetOverrunDto>,
    val unchangedEssentialRecurringCount: Int,
    val unchangedEssentialRecurringMonthly: BigDecimal,
    val cutCombinations: List<CutCombinationDto>,
    val otherMerchants: OtherMerchantsAggregateDto,
    val hasBudgets: Boolean,
) {
    val allFindings: List<SelectedFinding> get() = savings + whatChanged + budgets
}

/** Deterministic finding selection and ordering for the Spending review.
 *  Given identical inputs, always produces the exact same findings, order, figures, and simulation scenarios. */
object SpendingReviewSelector {

    data class Inputs(
        val currency: String,
        val mode: ReviewMode,
        val candidatesResponse: SpendingReviewCandidatesResponse,
        val monthDashboard: MonthDashboardResponse,
        val trailing12: TrailingResponse,
        val moversVsTrailing6: TopMoversResponse,
        val moversVsYearAgo: TopMoversResponse,
        val moverBreakdowns: Map<String, MoverCategoryBreakdown>,
        val budgetSummary: MonthSummaryResponse?,
        val paceProjection: MoneyFlowProjectionResponse?,
        val categories: List<CategoryView>,
    )

    fun select(inputs: Inputs): SelectedReview {
        val period = inputs.candidatesResponse.period
        val candidates = inputs.candidatesResponse.candidates
        val catByCode = inputs.categories.associateBy { it.code }
        val periodYm = YearMonth.of(period.year, period.month)
        val periodFrom = periodYm.atDay(1)
        val periodTo = if (period.reviewKind == "pace_review") period.windowTo else periodYm.atEndOfMonth()
        val windowFrom = period.windowFrom
        val hasBudgets = inputs.budgetSummary?.rows?.any { it.budget.signum() > 0 } == true
        val hasDeficit = inputs.monthDashboard.savings.signum() < 0 ||
            inputs.paceProjection?.deficit?.signum()?.let { it > 0 } == true

        // 1. Summary facts (always the exact same 4 pairs vs trailing-12m norm; null in savings_only mode).
        val summaryFacts = if (inputs.mode == ReviewMode.FULL) {
            buildSummaryFacts(period, inputs.monthDashboard, inputs.trailing12, inputs.paceProjection)
        } else null

        // 2. Savings section: fills in strict priority order up to MAX_SAVINGS_FINDINGS; sub-caps are ceilings.
        val savingsPicked = mutableListOf<SelectedFinding>()
        val selectedRecurringKeys = mutableSetOf<Pair<String, String>>()

        val allCancellable = candidates.recurringCharges
            .filter { it.action == RecurringAction.cancellable }
            .sortedWith(compareByDescending<RecurringChargeCandidateDto> { it.annualCost }.thenBy { it.id })
        val allFees = candidates.feesAndInterest.fees
            .filter { it.annualCost.signum() > 0 }
            .sortedWith(compareByDescending<FeeCandidateDto> { it.annualCost }.thenBy { it.id })
        val allNegotiableUp = candidates.recurringCharges
            .filter {
                it.action == RecurringAction.negotiable &&
                    it.yoyChangeKind == YoyChangeKind.price_increase &&
                    it.priorYearAmount != null &&
                    it.yoyDeltaPct != null
            }
            .sortedWith(
                compareByDescending<RecurringChargeCandidateDto> { it.yoyDeltaAbs ?: BigDecimal.ZERO }
                    .thenByDescending { it.annualCost }
                    .thenBy { it.id },
            )

        // Track every recurring payee key so merchants do not duplicate an existing recurring charge.
        for (r in candidates.recurringCharges) {
            selectedRecurringKeys += r.categoryCode to PayeeVariantClusterer.canonicalKey(r.merchant)
            for (v in r.mergedVariants) {
                selectedRecurringKeys += r.categoryCode to PayeeVariantClusterer.canonicalKey(v)
            }
        }
        val oneOffCategoryCodes = candidates.oneOffCategories.map { it.categoryCode }.toSet()

        val allMerchants = candidates.topNonEssentialMerchants
            .filter { m ->
                m.purchases >= SpendingReviewConstants.MIN_MERCHANT_PURCHASES &&
                    m.categoryCode !in oneOffCategoryCodes &&
                    (m.categoryCode to PayeeVariantClusterer.canonicalKey(m.merchant)) !in selectedRecurringKeys
            }
            .sortedWith(compareByDescending<TopMerchantCandidateDto> { it.total }.thenBy { it.id })

        val allHabitual = candidates.lumpyCategories
            .filter { it.monthlyMedian.signum() > 0 }
            .sortedWith(compareByDescending<LumpyCategoryCandidateDto> { it.trimToMedianAnnual }.thenBy { it.categoryCode })

        val totalEligibleSavings = allCancellable.size + allFees.size + allNegotiableUp.size + allMerchants.size + allHabitual.size

        // Cancellable charges and fees below SAVINGS_MIN_ANNUAL_EUR stay in Underlying data and count in "and N more".
        val topCancellable = allCancellable.filter { it.annualCost >= SpendingReviewConstants.SAVINGS_MIN_ANNUAL_EUR }
        val topFees = allFees.filter { it.annualCost >= SpendingReviewConstants.SAVINGS_MIN_ANNUAL_EUR }

        fun savingsRoom(): Int = (SpendingReviewConstants.MAX_SAVINGS_FINDINGS - savingsPicked.size).coerceAtLeast(0)

        // Priority 1: cancellable recurring charges >= SAVINGS_MIN_ANNUAL_EUR (delta -monthlyCost on category).
        for (r in topCancellable.take(minOf(SpendingReviewConstants.MAX_SAVINGS_CANCELLABLE, savingsRoom()))) {
            val figs = mutableListOf(
                FigureSlot("f1", "monthlyCost", FigureKind.MONEY, r.monthlyCost),
                FigureSlot("f2", "annualCost", FigureKind.MONEY, r.annualCost),
            )
            val ctx = mutableMapOf("cadence" to r.cadence.name)
            if ((r.yoyChangeKind == YoyChangeKind.price_increase || r.yoyChangeKind == YoyChangeKind.changed) &&
                r.priorYearAmount != null && r.yoyDeltaPct != null
            ) {
                figs += FigureSlot("f3", "priorYearAmount", FigureKind.MONEY, r.priorYearAmount)
                figs += FigureSlot("f4", "yoyDeltaPct", FigureKind.PERCENT, bd(kotlin.math.abs(r.yoyDeltaPct)))
                ctx["yoyChangeKind"] = r.yoyChangeKind.name
            }
            savingsPicked += SelectedFinding(
                id = "sav_${r.id}",
                section = FindingSection.savings,
                kind = FindingKind.cancellable_recurring,
                candidateId = r.id,
                payloadSubject = r.payloadLabel,
                localSubject = r.merchant,
                anonymized = r.anonymized,
                mergedVariants = r.mergedVariants,
                categoryCode = r.categoryCode,
                groupCode = r.groupCode,
                action = RecurringAction.cancellable,
                severity = "opportunity",
                changeKind = null,
                potentialMonthlySaving = r.monthlyCost,
                potentialAnnualSaving = r.annualCost,
                figures = figs,
                context = ctx,
                txFilter = r.txFilter,
                explorerLink = r.explorerLink,
                simulationScenario = categoryDeltaScenario(r.categoryCode, r.monthlyCost),
            )
        }

        // Priority 1b: avoidable financial fees >= SAVINGS_MIN_ANNUAL_EUR (delta -monthlyAverage on financial.fees).
        for (f in topFees.take(savingsRoom())) {
            savingsPicked += SelectedFinding(
                id = "sav_${f.id}",
                section = FindingSection.savings,
                kind = FindingKind.avoidable_fee,
                candidateId = f.id,
                payloadSubject = f.categoryCode,
                localSubject = null,
                anonymized = false,
                mergedVariants = emptyList(),
                categoryCode = f.categoryCode,
                groupCode = f.groupCode,
                action = RecurringAction.negotiable,
                severity = "warning",
                changeKind = null,
                potentialMonthlySaving = f.monthlyAverage,
                potentialAnnualSaving = f.annualCost,
                figures = listOf(
                    FigureSlot("f1", "total12m", FigureKind.MONEY, f.total12m),
                    FigureSlot("f2", "monthlyAverage", FigureKind.MONEY, f.monthlyAverage),
                    FigureSlot("f3", "occurrences", FigureKind.COUNT, BigDecimal.valueOf(f.occurrences.toLong())),
                ),
                context = emptyMap(),
                txFilter = f.txFilter,
                explorerLink = f.explorerLink,
                simulationScenario = categoryDeltaScenario(f.categoryCode, f.monthlyAverage),
            )
        }

        // Priority 2: negotiable recurring charges with a YoY price increase (compare/shop around, never cut scenario).
        for (r in allNegotiableUp.take(minOf(SpendingReviewConstants.MAX_SAVINGS_NEGOTIABLE, savingsRoom()))) {
            savingsPicked += SelectedFinding(
                id = "sav_${r.id}",
                section = FindingSection.savings,
                kind = FindingKind.negotiable_price_increase,
                candidateId = r.id,
                payloadSubject = r.payloadLabel,
                localSubject = r.merchant,
                anonymized = r.anonymized,
                mergedVariants = r.mergedVariants,
                categoryCode = r.categoryCode,
                groupCode = r.groupCode,
                action = RecurringAction.negotiable,
                severity = "warning",
                changeKind = null,
                potentialMonthlySaving = null,
                potentialAnnualSaving = null,
                figures = listOf(
                    FigureSlot("f1", "monthlyCost", FigureKind.MONEY, r.monthlyCost),
                    FigureSlot("f2", "priorYearAmount", FigureKind.MONEY, r.priorYearAmount!!),
                    FigureSlot("f3", "yoyDeltaPct", FigureKind.PERCENT, bd(kotlin.math.abs(r.yoyDeltaPct!!))),
                    FigureSlot("f4", "annualCost", FigureKind.MONEY, r.annualCost),
                ),
                context = mapOf("cadence" to r.cadence.name),
                txFilter = r.txFilter,
                explorerLink = r.explorerLink,
                simulationScenario = null,
            )
        }

        // Priority 3: top non-essential merchants (>= MIN_MERCHANT_PURCHASES, zero-median categories excluded).
        // When at least one habitual-uneven category qualifies, reserve one slot for it from the merchants ceiling.
        val reservedForHabitual = if (allHabitual.isNotEmpty()) 1 else 0
        val merchantCap = (SpendingReviewConstants.MAX_SAVINGS_MERCHANTS - reservedForHabitual).coerceAtLeast(0)
        val merchantRoom = (savingsRoom() - reservedForHabitual).coerceAtLeast(0)
        for (m in allMerchants.take(minOf(merchantCap, merchantRoom))) {
            savingsPicked += SelectedFinding(
                id = "sav_${m.id}",
                section = FindingSection.savings,
                kind = FindingKind.top_merchant,
                candidateId = m.id,
                payloadSubject = m.payloadLabel,
                localSubject = m.merchant,
                anonymized = m.anonymized,
                mergedVariants = m.mergedVariants,
                categoryCode = m.categoryCode,
                groupCode = m.groupCode,
                action = null,
                severity = "opportunity",
                changeKind = null,
                potentialMonthlySaving = null,
                potentialAnnualSaving = null,
                figures = listOf(
                    FigureSlot("f1", "total", FigureKind.MONEY, m.total),
                    FigureSlot("f2", "purchases", FigureKind.COUNT, BigDecimal.valueOf(m.purchases.toLong())),
                    FigureSlot("f3", "monthlyEquivalent", FigureKind.MONEY, m.monthlyEquivalent),
                    FigureSlot("f4", "shareOfCategoryPct", FigureKind.PERCENT, bd(m.shareOfCategoryPct)),
                ),
                context = emptyMap(),
                txFilter = m.txFilter,
                explorerLink = m.explorerLink,
                simulationScenario = null,
            )
        }

        // Priority 4: habitual-but-uneven categories (median > 0 and active >= LUMPY_MIN_ACTIVE_MONTHS).
        for (l in allHabitual.take(minOf(SpendingReviewConstants.MAX_SAVINGS_HABITUAL, savingsRoom()))) {
            savingsPicked += SelectedFinding(
                id = "sav_${l.id}",
                section = FindingSection.savings,
                kind = FindingKind.habitual_uneven,
                candidateId = l.id,
                payloadSubject = l.categoryCode,
                localSubject = null,
                anonymized = false,
                mergedVariants = emptyList(),
                categoryCode = l.categoryCode,
                groupCode = l.groupCode,
                action = null,
                severity = "opportunity",
                changeKind = null,
                potentialMonthlySaving = l.trimToMedianMonthly,
                potentialAnnualSaving = l.trimToMedianAnnual,
                figures = listOf(
                    FigureSlot("f1", "monthlyMean", FigureKind.MONEY, l.monthlyMean),
                    FigureSlot("f2", "monthlyMedian", FigureKind.MONEY, l.monthlyMedian),
                    FigureSlot("f3", "aboveMedianMonthly", FigureKind.MONEY, l.trimToMedianMonthly),
                    FigureSlot("f4", "aboveMedianAnnual", FigureKind.MONEY, l.trimToMedianAnnual),
                    FigureSlot("f5", "nonZeroMonths", FigureKind.COUNT, BigDecimal.valueOf(l.nonZeroMonths.toLong())),
                ),
                context = emptyMap(),
                txFilter = l.txFilter,
                explorerLink = l.explorerLink,
                simulationScenario = categoryDeltaScenario(l.categoryCode, l.trimToMedianMonthly),
            )
        }
        val savingsOmitted = (totalEligibleSavings - savingsPicked.size).coerceAtLeast(0)

        // 3. What changed section (skipped in savings_only mode).
        val changedPicked = mutableListOf<SelectedFinding>()
        val underlyingMovers = mutableListOf<UnderlyingMoverDto>()
        var whatChangedOmitted = 0

        if (inputs.mode == ReviewMode.FULL) {
            val mergedIncreases = mergeTopIncreases(inputs.moversVsTrailing6, inputs.moversVsYearAgo)
            for (inc in mergedIncreases) {
                val cat = catByCode[inc.categoryCode]
                underlyingMovers += UnderlyingMoverDto(
                    categoryCode = inc.categoryCode,
                    groupCode = cat?.group,
                    comparisonKind = inc.comparisonKind,
                    periodAmount = inc.periodAmount,
                    comparisonAmount = inc.comparisonAmount,
                    deltaAbs = inc.deltaAbs,
                    txFilter = TransactionLinkFilterDto(from = periodFrom, to = periodTo, direction = "expense", categoryCode = inc.categoryCode),
                    explorerLink = ExplorerLinkDto("category", inc.categoryCode, preset = "custom", from = windowFrom, to = periodTo),
                )
            }

            val nonePriceChanges = candidates.recurringCharges
                .filter {
                    it.action == RecurringAction.none &&
                        (it.yoyChangeKind == YoyChangeKind.price_increase || it.yoyChangeKind == YoyChangeKind.price_decrease) &&
                        it.priorYearAmount != null &&
                        it.yoyDeltaPct != null
                }
                .sortedWith(
                    compareByDescending<RecurringChargeCandidateDto> { (it.yoyDeltaAbs ?: BigDecimal.ZERO).abs() }
                        .thenBy { it.id },
                )

            val oneOffs = candidates.oneOffCategories
                .sortedWith(compareByDescending<LumpyCategoryCandidateDto> { it.monthlyMean }.thenBy { it.categoryCode })

            val totalEligibleChanged = mergedIncreases.size + nonePriceChanges.size + oneOffs.size
            fun changedRoom(): Int = (SpendingReviewConstants.MAX_CHANGED_FINDINGS - changedPicked.size).coerceAtLeast(0)

            // Priority 1: top period increases with pre-computed merchant drivers.
            for (inc in mergedIncreases.take(minOf(SpendingReviewConstants.MAX_CHANGED_INCREASES, changedRoom()))) {
                val cat = catByCode[inc.categoryCode]
                val bdBreakdown = inputs.moverBreakdowns[inc.categoryCode]
                val topDriver = bdBreakdown?.topMerchants?.firstOrNull()
                val figs = mutableListOf(
                    FigureSlot("f1", "periodAmount", FigureKind.MONEY, inc.periodAmount),
                    FigureSlot("f2", "deltaAbs", FigureKind.MONEY, inc.deltaAbs),
                    FigureSlot("f3", "comparisonAmount", FigureKind.MONEY, inc.comparisonAmount),
                )
                val ctx = mutableMapOf("comparisonKind" to inc.comparisonKind)
                if (bdBreakdown != null && topDriver != null) {
                    figs += FigureSlot("f4", "periodPurchases", FigureKind.COUNT, BigDecimal.valueOf(bdBreakdown.periodPurchases.toLong()))
                    figs += FigureSlot("f5", "topDriverTotal", FigureKind.MONEY, topDriver.total)
                    figs += FigureSlot("f6", "topDriverSharePct", FigureKind.PERCENT, bd(topDriver.shareOfCategoryPct))
                    figs += FigureSlot("f7", "topDriverPurchases", FigureKind.COUNT, BigDecimal.valueOf(topDriver.purchases.toLong()))
                    val driverKind = when {
                        !topDriver.anonymized -> "named"
                        topDriver.merchant.startsWith("Unlabelled purchase") -> "unlabelled"
                        else -> "anonymized"
                    }
                    ctx["topDriverKind"] = driverKind
                    if (driverKind == "named") {
                        ctx["topDriverName"] = topDriver.merchant
                    }
                }
                changedPicked += SelectedFinding(
                    id = "chg_inc_${inc.categoryCode.replace('.', '_')}",
                    section = FindingSection.whatChanged,
                    kind = FindingKind.period_increase,
                    candidateId = null,
                    payloadSubject = inc.categoryCode,
                    localSubject = null,
                    anonymized = false,
                    mergedVariants = emptyList(),
                    categoryCode = inc.categoryCode,
                    groupCode = cat?.group,
                    action = null,
                    severity = if (hasDeficit) "critical" else "warning",
                    changeKind = "drift",
                    potentialMonthlySaving = null,
                    potentialAnnualSaving = null,
                    figures = figs,
                    context = ctx,
                    txFilter = TransactionLinkFilterDto(from = periodFrom, to = periodTo, direction = "expense", categoryCode = inc.categoryCode),
                    explorerLink = ExplorerLinkDto("category", inc.categoryCode, preset = "custom", from = windowFrom, to = periodTo),
                    simulationScenario = null,
                )
            }

            // Priority 2: price changes on items with action == none (stated as facts, no suggestion).
            for (r in nonePriceChanges.take(minOf(SpendingReviewConstants.MAX_CHANGED_NONE_PRICES, changedRoom()))) {
                val dir = if (r.yoyChangeKind == YoyChangeKind.price_decrease) "down" else "up"
                changedPicked += SelectedFinding(
                    id = "chg_${r.id}",
                    section = FindingSection.whatChanged,
                    kind = FindingKind.non_actionable_price_change,
                    candidateId = r.id,
                    payloadSubject = r.payloadLabel,
                    localSubject = r.merchant,
                    anonymized = r.anonymized,
                    mergedVariants = r.mergedVariants,
                    categoryCode = r.categoryCode,
                    groupCode = r.groupCode,
                    action = RecurringAction.none,
                    severity = "warning",
                    changeKind = if (dir == "up") "price_increase" else "drop",
                    potentialMonthlySaving = null,
                    potentialAnnualSaving = null,
                    figures = listOf(
                        FigureSlot("f1", "monthlyCost", FigureKind.MONEY, r.monthlyCost),
                        FigureSlot("f2", "priorYearAmount", FigureKind.MONEY, r.priorYearAmount!!),
                        FigureSlot("f3", "yoyDeltaPct", FigureKind.PERCENT, bd(kotlin.math.abs(r.yoyDeltaPct!!))),
                        FigureSlot("f4", "annualCost", FigureKind.MONEY, r.annualCost),
                    ),
                    context = mapOf("direction" to dir, "cadence" to r.cadence.name),
                    txFilter = r.txFilter,
                    explorerLink = r.explorerLink,
                    simulationScenario = null,
                )
            }

            // Priority 3: zero-median one-offs (moved here from Savings).
            for (o in oneOffs.take(minOf(SpendingReviewConstants.MAX_CHANGED_ONE_OFFS, changedRoom()))) {
                val total12m = Money.normalize(o.monthlyMean.multiply(BigDecimal.valueOf(period.monthsAvailable.coerceAtLeast(1).toLong())))
                changedPicked += SelectedFinding(
                    id = "chg_${o.id}",
                    section = FindingSection.whatChanged,
                    kind = FindingKind.zero_median_one_off,
                    candidateId = o.id,
                    payloadSubject = o.categoryCode,
                    localSubject = null,
                    anonymized = false,
                    mergedVariants = emptyList(),
                    categoryCode = o.categoryCode,
                    groupCode = o.groupCode,
                    action = null,
                    severity = "opportunity",
                    changeKind = "one_off",
                    potentialMonthlySaving = null,
                    potentialAnnualSaving = null,
                    figures = listOf(
                        FigureSlot("f1", "total12m", FigureKind.MONEY, total12m),
                        FigureSlot("f2", "nonZeroMonths", FigureKind.COUNT, BigDecimal.valueOf(o.nonZeroMonths.toLong())),
                        FigureSlot("f3", "peakMonthAmount", FigureKind.MONEY, o.peakMonthAmount),
                    ),
                    context = emptyMap(),
                    txFilter = o.txFilter,
                    explorerLink = o.explorerLink,
                    simulationScenario = null,
                )
            }
            whatChangedOmitted = (totalEligibleChanged - changedPicked.size).coerceAtLeast(0)
        }

        // 4. Budgets section: overruns only (skipped in savings_only mode or when no budgets).
        val budgetsPicked = mutableListOf<SelectedFinding>()
        val underlyingOverruns = mutableListOf<UnderlyingBudgetOverrunDto>()
        var budgetsOmitted = 0

        if (inputs.mode == ReviewMode.FULL && hasBudgets) {
            val overruns = inputs.budgetSummary.rows
                .filter { it.budget.signum() > 0 && it.spent > it.budget }
                .sortedWith(
                    compareByDescending<MonthSummaryRow> { it.spent - it.budget }
                        .thenBy { it.categoryCode },
                )

            for (row in overruns) {
                val overUnder = Money.normalize(row.spent - row.budget)
                underlyingOverruns += UnderlyingBudgetOverrunDto(
                    categoryCode = row.categoryCode,
                    budget = row.budget,
                    spent = row.spent,
                    overUnder = overUnder,
                    pctConsumed = row.percent,
                    txFilter = TransactionLinkFilterDto(from = periodFrom, to = periodTo, direction = "expense", categoryCode = row.categoryCode),
                    explorerLink = ExplorerLinkDto("category", row.categoryCode, preset = "custom", from = windowFrom, to = periodTo),
                )
            }

            for (row in overruns.take(SpendingReviewConstants.MAX_BUDGET_FINDINGS)) {
                val overUnder = Money.normalize(row.spent - row.budget)
                val useMultiple = row.percent >= 200.0
                val ratioSlot = if (useMultiple) {
                    val mult = row.spent.divide(row.budget, 1, RoundingMode.HALF_UP)
                    FigureSlot("f4", "overrunMultiple", FigureKind.MULTIPLE, mult)
                } else {
                    FigureSlot("f4", "pctConsumed", FigureKind.PERCENT, bd(row.percent))
                }
                budgetsPicked += SelectedFinding(
                    id = "bud_${row.categoryCode.replace('.', '_')}",
                    section = FindingSection.budgets,
                    kind = FindingKind.budget_overrun,
                    candidateId = null,
                    payloadSubject = row.categoryCode,
                    localSubject = null,
                    anonymized = false,
                    mergedVariants = emptyList(),
                    categoryCode = row.categoryCode,
                    groupCode = catByCode[row.categoryCode]?.group,
                    action = null,
                    severity = if (hasDeficit) "critical" else "warning",
                    changeKind = null,
                    potentialMonthlySaving = null,
                    potentialAnnualSaving = null,
                    figures = listOf(
                        FigureSlot("f1", "spent", FigureKind.MONEY, Money.normalize(row.spent)),
                        FigureSlot("f2", "budget", FigureKind.MONEY, Money.normalize(row.budget)),
                        FigureSlot("f3", "overUnder", FigureKind.MONEY, overUnder),
                        ratioSlot,
                    ),
                    context = mapOf("overrunFormat" to if (useMultiple) "multiple" else "percent"),
                    txFilter = TransactionLinkFilterDto(from = periodFrom, to = periodTo, direction = "expense", categoryCode = row.categoryCode),
                    explorerLink = ExplorerLinkDto("category", row.categoryCode, preset = "custom", from = windowFrom, to = periodTo),
                    simulationScenario = null,
                )
            }
            budgetsOmitted = (overruns.size - budgetsPicked.size).coerceAtLeast(0)
        }

        val unchangedEssential = candidates.recurringCharges.filter {
            it.essential && it.yoyChangeKind != YoyChangeKind.price_increase && it.yoyChangeKind != YoyChangeKind.changed
        }
        val unchangedEssentialMonthly = Money.normalize(unchangedEssential.fold(BigDecimal.ZERO) { a, r -> a + r.monthlyCost })

        return SelectedReview(
            mode = inputs.mode,
            currency = inputs.currency,
            period = period.copy(
                reviewKind = if (inputs.mode == ReviewMode.SAVINGS_ONLY) "savings_only" else period.reviewKind,
            ),
            summaryFacts = summaryFacts,
            savings = savingsPicked,
            whatChanged = changedPicked,
            budgets = budgetsPicked,
            savingsOmitted = savingsOmitted,
            whatChangedOmitted = whatChangedOmitted,
            budgetsOmitted = budgetsOmitted,
            underlyingMovers = underlyingMovers,
            underlyingBudgetOverruns = underlyingOverruns,
            unchangedEssentialRecurringCount = unchangedEssential.size,
            unchangedEssentialRecurringMonthly = unchangedEssentialMonthly,
            cutCombinations = candidates.cutCombinations,
            otherMerchants = candidates.otherMerchants,
            hasBudgets = hasBudgets,
        )
    }

    private fun buildSummaryFacts(
        period: SpendingReviewPeriodDto,
        md: MonthDashboardResponse,
        trailing12: TrailingResponse,
        paceProjection: MoneyFlowProjectionResponse?,
    ): SelectedSummaryFacts {
        val t12 = trailing12.summary
        val normSavingsRate = if (t12.avgIncome.signum() > 0) {
            t12.avgNetSavings.divide(t12.avgIncome, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
        } else 0.0

        val expDelta = Money.normalize(md.expenses - t12.avgExpenses)
        val incDelta = Money.normalize(md.income - t12.avgIncome)

        val figs = mutableListOf(
            FigureSlot("f1", "periodExpenses", FigureKind.MONEY, Money.normalize(md.expenses)),
            FigureSlot("f2", "normExpenses", FigureKind.MONEY, Money.normalize(t12.avgExpenses)),
            FigureSlot("f3", "expensesDeltaAbs", FigureKind.MONEY, expDelta.abs()),
            FigureSlot("f4", "periodIncome", FigureKind.MONEY, Money.normalize(md.income)),
            FigureSlot("f5", "normIncome", FigureKind.MONEY, Money.normalize(t12.avgIncome)),
            FigureSlot("f6", "incomeDeltaAbs", FigureKind.MONEY, incDelta.abs()),
            FigureSlot("f7", "periodNetSavings", FigureKind.MONEY, Money.normalize(md.savings)),
            FigureSlot("f8", "normNetSavings", FigureKind.MONEY, Money.normalize(t12.avgNetSavings)),
            FigureSlot("f9", "periodSavingsRatePct", FigureKind.PERCENT, bd(md.savingsRate)),
            FigureSlot("f10", "normSavingsRatePct", FigureKind.PERCENT, bd(normSavingsRate)),
        )
        if (paceProjection != null) {
            val projSavings = Money.normalize(paceProjection.income - paceProjection.expenses)
            figs += FigureSlot("f11", "projectedExpenses", FigureKind.MONEY, Money.normalize(paceProjection.expenses))
            figs += FigureSlot("f12", "projectedNetSavings", FigureKind.MONEY, projSavings)
            figs += FigureSlot("f13", "daysElapsed", FigureKind.COUNT, BigDecimal.valueOf(period.daysElapsed.toLong()))
            figs += FigureSlot("f14", "daysInMonth", FigureKind.COUNT, BigDecimal.valueOf(period.daysInMonth.toLong()))
        }

        val expDir = directionAgainstNorm(md.expenses, t12.avgExpenses)
        val incDir = directionAgainstNorm(md.income, t12.avgIncome)
        val savDir = directionAgainstNorm(md.savings, t12.avgNetSavings)
        val (diagnosis, diagnosisDetail) = diagnoseSummary(expDir, incDir, savDir, expDelta.abs(), incDelta.abs())

        return SelectedSummaryFacts(
            periodYear = period.year,
            periodMonth = period.month,
            reviewKind = period.reviewKind,
            daysElapsed = period.daysElapsed,
            daysInMonth = period.daysInMonth,
            expensesDirection = expDir,
            incomeDirection = incDir,
            savingsDirection = savDir,
            diagnosis = diagnosis,
            diagnosisDetail = diagnosisDetail,
            hasDeficit = md.savings.signum() < 0,
            hasProjectedDeficit = paceProjection?.deficit?.signum()?.let { it > 0 } == true,
            figures = figs,
        )
    }

    internal fun directionAgainstNorm(period: BigDecimal, norm: BigDecimal): String {
        val delta = Money.normalize(period - norm)
        val absDelta = delta.abs()
        if (absDelta <= SpendingReviewConstants.SUMMARY_IN_LINE_ABS_EUR) return "at"
        if (norm.abs().signum() > 0) {
            val pct = absDelta.divide(norm.abs(), 4, RoundingMode.HALF_EVEN).multiply(BigDecimal("100"))
            if (pct <= SpendingReviewConstants.SUMMARY_IN_LINE_PCT) return "at"
        }
        return if (delta.signum() < 0) "below" else "above"
    }

    internal fun diagnoseSummary(
        expDir: String,
        incDir: String,
        savDir: String,
        expDeltaAbs: BigDecimal,
        incDeltaAbs: BigDecimal,
    ): Pair<SummaryDiagnosis, String> {
        if (savDir == "below") {
            return when {
                expDir != "above" -> SummaryDiagnosis.shortfall_from_income to
                    if (expDir == "at") "expenses_in_line" else "expenses_below"
                incDir != "below" -> SummaryDiagnosis.shortfall_from_spending to
                    if (incDir == "at") "income_in_line" else "income_above"
                else -> {
                    val dominant = when {
                        expDeltaAbs > incDeltaAbs -> "spending_dominant"
                        incDeltaAbs > expDeltaAbs -> "income_dominant"
                        else -> "equal"
                    }
                    SummaryDiagnosis.shortfall_from_both to dominant
                }
            }
        }
        val detail = when {
            savDir == "at" -> "in_line"
            expDir == "below" && incDir == "above" -> "both_favorable"
            expDir == "below" -> "lower_spending"
            incDir == "above" -> "higher_income"
            else -> "in_line"
        }
        return SummaryDiagnosis.on_track_or_above to detail
    }

    private data class MergedIncrease(
        val categoryCode: String,
        val comparisonKind: String,
        val periodAmount: BigDecimal,
        val comparisonAmount: BigDecimal,
        val deltaAbs: BigDecimal,
    )

    private fun mergeTopIncreases(
        vsTrailing6: TopMoversResponse,
        vsYearAgo: TopMoversResponse,
    ): List<MergedIncrease> {
        val byCat = LinkedHashMap<String, MergedIncrease>()
        for (r in vsTrailing6.increases) {
            byCat[r.categoryCode] = MergedIncrease(
                categoryCode = r.categoryCode,
                comparisonKind = "trailing6_avg",
                periodAmount = Money.normalize(r.periodAmount),
                comparisonAmount = Money.normalize(r.baselineAmount),
                deltaAbs = Money.normalize(r.deltaAbs),
            )
        }
        for (r in vsYearAgo.increases) {
            val cand = MergedIncrease(
                categoryCode = r.categoryCode,
                comparisonKind = "year_ago",
                periodAmount = Money.normalize(r.periodAmount),
                comparisonAmount = Money.normalize(r.baselineAmount),
                deltaAbs = Money.normalize(r.deltaAbs),
            )
            val existing = byCat[r.categoryCode]
            if (existing == null || cand.deltaAbs > existing.deltaAbs) {
                byCat[r.categoryCode] = cand
            }
        }
        return byCat.values.sortedWith(compareByDescending<MergedIncrease> { it.deltaAbs }.thenBy { it.categoryCode })
    }

    /** Reduces a category's monthly projection by the specific delta amount (never freezes the whole category). */
    private fun categoryDeltaScenario(categoryCode: String, monthlyDelta: BigDecimal): ProjectionScenario? {
        if (monthlyDelta.signum() <= 0) return null
        return ProjectionScenario(
            freeze = emptyList(),
            adjustments = listOf(
                ProjectionAdjustment(
                    scope = ScopeRef(ScopeType.category, categoryCode),
                    kind = AdjustmentKind.amount,
                    value = Money.normalize(monthlyDelta).negate(),
                ),
            ),
            level = "category",
            windowMonths = null,
            modeOverride = null,
            savingsTarget = null,
        )
    }

    private fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN)
}

internal fun ProjectionScenario.toScenarioDto(): ProjectionScenarioDto = ProjectionScenarioDto(
    freeze = freeze.map { ScopeDto(it.type, it.code) },
    adjustments = adjustments.map { a ->
        ProjectionAdjustmentDtoCompat(a.scope, a.kind, a.value)
    },
    level = level,
    windowMonths = windowMonths,
    modeOverride = modeOverride,
    savingsTarget = savingsTarget,
)

private fun ProjectionAdjustmentDtoCompat(
    scope: ScopeRef,
    kind: AdjustmentKind,
    value: BigDecimal,
) = com.sephilabs.sharedledger.analytics.question.ProjectionAdjustmentDto(
    ScopeDto(scope.type, scope.code),
    kind,
    value,
)
