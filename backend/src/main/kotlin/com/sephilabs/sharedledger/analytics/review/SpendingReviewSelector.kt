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
import com.sephilabs.sharedledger.analytics.recurring.PayeeVariantClusterer
import com.sephilabs.sharedledger.analytics.recurring.RecurringAction
import com.sephilabs.sharedledger.analytics.recurring.YoyChangeKind
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
    income_change,
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
    val incomeVsMedianContext: String = "in_line", // "normal_month_mean_skewed" | "below_typical" | "above_typical" | "in_line"
    val expensesVsMedianContext: String = "in_line", // "normal_month_mean_skewed" | "below_typical" | "above_typical" | "in_line"
    val hasDeficit: Boolean,
    val hasProjectedDeficit: Boolean,
    val figures: List<FigureSlot>,
)

data class SelectedFinding(
    val id: String,
    val section: FindingSection,
    val kind: FindingKind,
    val candidateId: String?,
    val subjectKey: String? = null,
    val subjectKind: DecisionSubjectKind? = null,
    val memberKeys: List<String> = emptyList(),
    val amountAtDecision: BigDecimal? = null,
    val decision: ActiveDecisionBadgeDto? = null,
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
    val sinceLastMonth: List<SinceLastMonthItemDto> = emptyList(),
    val savings: List<SelectedFinding>,
    val whatChanged: List<SelectedFinding>,
    val budgets: List<SelectedFinding>,
    val upcomingCharges: List<UpcomingChargeDto> = emptyList(),
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

data class IncomeCategoryMover(
    val categoryCode: String,
    val groupCode: String?,
    val periodAmount: BigDecimal,
    val avg12m: BigDecimal,
    val median12m: BigDecimal,
)

data class BudgetHistoryStats(
    val trailing6mAvg: BigDecimal,
    val median12m: BigDecimal,
    val total12m: BigDecimal,
    val exceededMonths12m: Int,
    val exceededMonthsTotal12m: BigDecimal,
    val windowMonths: Int,
)

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
        val incomeMovers: List<IncomeCategoryMover> = emptyList(),
        val budgetHistoryStats: Map<String, BudgetHistoryStats> = emptyMap(),
        val upcomingCharges: List<UpcomingChargeDto> = emptyList(),
        val sinceLastMonth: List<SinceLastMonthItemDto> = emptyList(),
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

        // 2. Savings section: apply user decisions BEFORE selection so freed slots fill with the next findings.
        fun isHiddenFromSavings(decision: ActiveDecisionBadgeDto?): Boolean =
            decision != null && (
                decision.decisionType == SpendingReviewDecisionType.keep ||
                    (decision.decisionType == SpendingReviewDecisionType.compared && decision.status == "snoozed")
                )

        val savingsPicked = mutableListOf<SelectedFinding>()
        val selectedRecurringKeys = mutableSetOf<Pair<String, String>>()

        val allCancellable = candidates.recurringCharges
            .filter { it.action == RecurringAction.cancellable && !isHiddenFromSavings(it.decision) }
            .sortedWith(compareByDescending<RecurringChargeCandidateDto> { it.annualCost }.thenBy { it.id })
        val allFees = candidates.feesAndInterest.fees
            .filter { it.annualCost.signum() > 0 }
            .sortedWith(compareByDescending<FeeCandidateDto> { it.annualCost }.thenBy { it.id })
        val allNegotiableUp = candidates.recurringCharges
            .filter {
                it.action == RecurringAction.negotiable &&
                    !isHiddenFromSavings(it.decision) &&
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
                !isHiddenFromSavings(m.decision) &&
                    m.purchases >= SpendingReviewConstants.MIN_MERCHANT_PURCHASES &&
                    m.categoryCode !in oneOffCategoryCodes &&
                    (m.categoryCode to PayeeVariantClusterer.canonicalKey(m.merchant)) !in selectedRecurringKeys
            }
            .sortedWith(compareByDescending<TopMerchantCandidateDto> { it.total }.thenBy { it.id })

        // Option A: dominant-peak categories (>= HABITUAL_PEAK_OUTLIER_MIN_SHARE of 12m above-median excess
        // coming from a single peak month) are excluded from Savings; they stay in What changed & Underlying data.
        val activeWindowMonths = BigDecimal.valueOf(period.monthsAvailable.coerceAtLeast(1).toLong())
        val allHabitual = candidates.lumpyCategories
            .filter { l ->
                if (l.monthlyMedian.signum() <= 0) return@filter false
                val peakAboveMed = (l.peakMonthAmount - l.monthlyMedian).max(BigDecimal.ZERO)
                val totalExcess = l.trimToMedianMonthly.multiply(activeWindowMonths)
                val peakShare = if (totalExcess.signum() > 0) {
                    peakAboveMed.divide(totalExcess, 4, RoundingMode.HALF_EVEN)
                } else BigDecimal.ZERO
                peakShare < SpendingReviewConstants.HABITUAL_PEAK_OUTLIER_MIN_SHARE
            }
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
                subjectKey = r.subjectKey.ifBlank { null },
                subjectKind = DecisionSubjectKind.recurring,
                memberKeys = r.memberKeys,
                amountAtDecision = r.monthlyCost,
                decision = r.decision,
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
                subjectKey = r.subjectKey.ifBlank { null },
                subjectKind = DecisionSubjectKind.recurring,
                memberKeys = r.memberKeys,
                amountAtDecision = r.monthlyCost,
                decision = r.decision,
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
                subjectKey = m.subjectKey.ifBlank { null },
                subjectKind = DecisionSubjectKind.merchant,
                memberKeys = m.memberKeys,
                amountAtDecision = m.monthlyEquivalent,
                decision = m.decision,
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
            val peakAboveMed = (l.peakMonthAmount - l.monthlyMedian).max(BigDecimal.ZERO)
            val totalExcess = l.trimToMedianMonthly.multiply(BigDecimal.valueOf(period.monthsAvailable.coerceAtLeast(1).toLong()))
            val peakShare = if (totalExcess.signum() > 0) {
                peakAboveMed.divide(totalExcess, 4, RoundingMode.HALF_EVEN)
            } else BigDecimal.ZERO
            val peakDominated = peakShare >= SpendingReviewConstants.HABITUAL_PEAK_OUTLIER_MIN_SHARE
            val habFigs = mutableListOf(
                FigureSlot("f1", "monthlyMean", FigureKind.MONEY, l.monthlyMean),
                FigureSlot("f2", "monthlyMedian", FigureKind.MONEY, l.monthlyMedian),
                FigureSlot("f3", "aboveMedianMonthly", FigureKind.MONEY, l.trimToMedianMonthly),
                FigureSlot("f4", "aboveMedianAnnual", FigureKind.MONEY, l.trimToMedianAnnual),
                FigureSlot("f5", "nonZeroMonths", FigureKind.COUNT, BigDecimal.valueOf(l.nonZeroMonths.toLong())),
            )
            val habCtx = mutableMapOf<String, String>()
            if (peakDominated) {
                habFigs += FigureSlot("f6", "peakMonthAmount", FigureKind.MONEY, Money.normalize(l.peakMonthAmount))
                habFigs += FigureSlot(
                    "f7",
                    "peakExcessSharePct",
                    FigureKind.PERCENT,
                    bd(peakShare.multiply(BigDecimal("100")).min(BigDecimal("100")).toDouble()),
                )
                habCtx["peakDominated"] = "true"
            }
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
                figures = habFigs,
                context = habCtx,
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
                    val isKept = it.decision?.decisionType == SpendingReviewDecisionType.keep
                    (it.action == RecurringAction.none || isKept) &&
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

            val incomeDir = summaryFacts?.incomeDirection
            val qualifyingIncomeMovers = when (incomeDir) {
                "below" -> inputs.incomeMovers
                    .filter { (it.periodAmount - it.median12m) < SpendingReviewConstants.SUMMARY_IN_LINE_ABS_EUR.negate() }
                    .sortedWith(compareBy<IncomeCategoryMover> { it.periodAmount - it.median12m }.thenBy { it.categoryCode })
                "above" -> inputs.incomeMovers
                    .filter { (it.periodAmount - it.median12m) > SpendingReviewConstants.SUMMARY_IN_LINE_ABS_EUR }
                    .sortedWith(compareByDescending<IncomeCategoryMover> { it.periodAmount - it.median12m }.thenBy { it.categoryCode })
                else -> emptyList()
            }

            val totalEligibleChanged = qualifyingIncomeMovers.size + mergedIncreases.size + nonePriceChanges.size + oneOffs.size
            fun changedRoom(): Int = (SpendingReviewConstants.MAX_CHANGED_FINDINGS - changedPicked.size).coerceAtLeast(0)

            // Priority 0: top income mover whenever monthly income deviated from its median (explains genuine income shortfalls/spikes).
            for (incMover in qualifyingIncomeMovers.take(minOf(SpendingReviewConstants.MAX_CHANGED_INCOME, changedRoom()))) {
                val deltaAvg = Money.normalize(incMover.periodAmount - incMover.avg12m)
                val deltaMed = Money.normalize(incMover.periodAmount - incMover.median12m)
                val dir = if (deltaMed.signum() < 0) "below" else "above"
                val vsMedian = directionAgainstNorm(incMover.periodAmount, incMover.median12m)
                val medianDiffers = directionAgainstNorm(incMover.median12m, incMover.avg12m) != "at"
                val figs = mutableListOf(
                    FigureSlot("f1", "periodAmount", FigureKind.MONEY, Money.normalize(incMover.periodAmount)),
                    FigureSlot("f2", "deltaVsAvgAbs", FigureKind.MONEY, deltaAvg.abs()),
                    FigureSlot("f3", "avg12m", FigureKind.MONEY, Money.normalize(incMover.avg12m)),
                )
                if (medianDiffers) {
                    figs += FigureSlot("f4", "median12m", FigureKind.MONEY, Money.normalize(incMover.median12m))
                    figs += FigureSlot("f5", "deltaVsMedianAbs", FigureKind.MONEY, deltaMed.abs())
                }
                changedPicked += SelectedFinding(
                    id = "chg_inc_income_${incMover.categoryCode.replace('.', '_')}",
                    section = FindingSection.whatChanged,
                    kind = FindingKind.income_change,
                    candidateId = null,
                    payloadSubject = incMover.categoryCode,
                    localSubject = null,
                    anonymized = false,
                    mergedVariants = emptyList(),
                    categoryCode = incMover.categoryCode,
                    groupCode = incMover.groupCode ?: catByCode[incMover.categoryCode]?.group,
                    action = null,
                    severity = if (dir == "below" && (hasDeficit || summaryFacts?.savingsDirection == "below")) "warning" else "opportunity",
                    changeKind = if (dir == "below") "drop" else "drift",
                    potentialMonthlySaving = null,
                    potentialAnnualSaving = null,
                    figures = figs,
                    context = mapOf(
                        "direction" to dir,
                        "vsMedian" to vsMedian,
                        "medianDiffers" to medianDiffers.toString(),
                    ),
                    txFilter = TransactionLinkFilterDto(from = windowFrom, to = periodTo, direction = "income", categoryCode = incMover.categoryCode),
                    explorerLink = null,
                    simulationScenario = null,
                )
            }

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
                val isUnlabelledDriver = ctx["topDriverKind"] == "unlabelled"
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
                    txFilter = TransactionLinkFilterDto(
                        from = periodFrom,
                        to = periodTo,
                        direction = "expense",
                        categoryCode = inc.categoryCode,
                        unlabelled = if (isUnlabelledDriver) true else null,
                    ),
                    explorerLink = ExplorerLinkDto("category", inc.categoryCode, preset = "custom", from = windowFrom, to = periodTo),
                    simulationScenario = null,
                )
            }

            // Priority 2: price changes on items with action == none or kept recurring charges (stated as facts, no suggestion).
            for (r in nonePriceChanges.take(minOf(SpendingReviewConstants.MAX_CHANGED_NONE_PRICES, changedRoom()))) {
                val dir = if (r.yoyChangeKind == YoyChangeKind.price_decrease) "down" else "up"
                changedPicked += SelectedFinding(
                    id = "chg_${r.id}",
                    section = FindingSection.whatChanged,
                    kind = FindingKind.non_actionable_price_change,
                    candidateId = r.id,
                    subjectKey = r.subjectKey.ifBlank { null },
                    subjectKind = DecisionSubjectKind.recurring,
                    memberKeys = r.memberKeys,
                    amountAtDecision = r.monthlyCost,
                    decision = r.decision,
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
                val stats = inputs.budgetHistoryStats[row.categoryCode]
                val trailingAvg = stats?.trailing6mAvg?.let { Money.normalize(it) }
                val hasTrailing = trailingAvg != null && trailingAvg.signum() > 0
                val underSized = hasTrailing && trailingAvg > row.budget.multiply(SpendingReviewConstants.BUDGET_UNDERESTIMATED_MIN_RATIO)
                val nearZeroMedian = stats != null &&
                    stats.median12m <= row.budget.multiply(SpendingReviewConstants.BUDGET_CONCENTRATED_MEDIAN_MAX_RATIO)
                val concentrated = stats != null &&
                    stats.windowMonths >= 6 &&
                    stats.exceededMonths12m in 1..(stats.windowMonths / 2) &&
                    stats.total12m.signum() > 0 &&
                    stats.exceededMonthsTotal12m.divide(stats.total12m, 4, RoundingMode.HALF_EVEN) >=
                        SpendingReviewConstants.BUDGET_CONCENTRATED_TOP_SHARE_MIN
                val budgetFit = when {
                    nearZeroMedian && concentrated && (underSized || stats.exceededMonths12m >= 2) -> "annual_better"
                    underSized -> "under_sized"
                    else -> "genuine_overspend"
                }
                val budgetFigs = mutableListOf(
                    FigureSlot("f1", "spent", FigureKind.MONEY, Money.normalize(row.spent)),
                    FigureSlot("f2", "budget", FigureKind.MONEY, Money.normalize(row.budget)),
                    FigureSlot("f3", "overUnder", FigureKind.MONEY, overUnder),
                    ratioSlot,
                )
                if (hasTrailing) {
                    budgetFigs += FigureSlot("f5", "trailing6mAvg", FigureKind.MONEY, trailingAvg)
                }
                if (stats != null && stats.windowMonths > 0) {
                    budgetFigs += FigureSlot("f6", "exceededMonths12m", FigureKind.COUNT, BigDecimal.valueOf(stats.exceededMonths12m.toLong()))
                    budgetFigs += FigureSlot("f7", "windowMonths", FigureKind.COUNT, BigDecimal.valueOf(stats.windowMonths.toLong()))
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
                    figures = budgetFigs,
                    context = mapOf(
                        "overrunFormat" to if (useMultiple) "multiple" else "percent",
                        "budgetFit" to budgetFit,
                    ),
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
            sinceLastMonth = inputs.sinceLastMonth,
            savings = savingsPicked,
            whatChanged = changedPicked,
            budgets = budgetsPicked,
            upcomingCharges = inputs.upcomingCharges,
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

        val hasSeries = trailing12.points.size >= 3
        val medianExpenses = if (hasSeries) Money.normalize(medianDecimal(trailing12.points.map { it.expenses })) else Money.normalize(t12.avgExpenses)
        val medianIncome = if (hasSeries) Money.normalize(medianDecimal(trailing12.points.map { it.income })) else Money.normalize(t12.avgIncome)
        val medianSavings = if (hasSeries) Money.normalize(medianDecimal(trailing12.points.map { it.netSavings })) else Money.normalize(t12.avgNetSavings)

        // Diagnosis is decided against median expenses, median income, and median savings so
        // exceptional bonus or travel months never distort a typical month's diagnosis.
        val expDirVsMean = directionAgainstNorm(md.expenses, t12.avgExpenses)
        val expDir = directionAgainstNorm(md.expenses, medianExpenses)
        val incDirVsMean = directionAgainstNorm(md.income, t12.avgIncome)
        val incDir = directionAgainstNorm(md.income, medianIncome)
        val savDir = directionAgainstNorm(md.savings, medianSavings)
        val expDeltaVsMedianAbs = Money.normalize(md.expenses - medianExpenses).abs()
        val incDeltaVsMedianAbs = Money.normalize(md.income - medianIncome).abs()
        val (diagnosis, diagnosisDetail) = diagnoseSummary(expDir, incDir, savDir, expDeltaVsMedianAbs, incDeltaVsMedianAbs)

        var incomeVsMedianContext = "in_line"
        var expensesVsMedianContext = "in_line"
        if (hasSeries) {
            val medianDiffersFromMean = directionAgainstNorm(medianIncome, t12.avgIncome) != "at"
            val savMedianDiffersFromMean = directionAgainstNorm(medianSavings, t12.avgNetSavings) != "at"
            val expMedianDiffersFromMean = directionAgainstNorm(medianExpenses, t12.avgExpenses) != "at"
            if (medianDiffersFromMean) {
                figs += FigureSlot("f15", "normMedianIncome", FigureKind.MONEY, medianIncome)
                figs += FigureSlot("f16", "incomeVsMedianDeltaAbs", FigureKind.MONEY, incDeltaVsMedianAbs)
                incomeVsMedianContext = when {
                    incDirVsMean == "below" && incDir != "below" -> "normal_month_mean_skewed"
                    incDir == "below" -> "below_typical"
                    incDir == "above" -> "above_typical"
                    else -> "in_line"
                }
            }
            if (savMedianDiffersFromMean) {
                figs += FigureSlot("f17", "normMedianSavings", FigureKind.MONEY, medianSavings)
            }
            if (expMedianDiffersFromMean) {
                figs += FigureSlot("f18", "normMedianExpenses", FigureKind.MONEY, medianExpenses)
                figs += FigureSlot("f19", "expensesVsMedianDeltaAbs", FigureKind.MONEY, expDeltaVsMedianAbs)
                expensesVsMedianContext = when {
                    expDirVsMean == "below" && expDir != "below" -> "normal_month_mean_skewed"
                    expDir == "below" -> "below_typical"
                    expDir == "above" -> "above_typical"
                    else -> "in_line"
                }
            }
        }

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
            incomeVsMedianContext = incomeVsMedianContext,
            expensesVsMedianContext = expensesVsMedianContext,
            hasDeficit = md.savings.signum() < 0,
            hasProjectedDeficit = paceProjection?.deficit?.signum()?.let { it > 0 } == true,
            figures = figs,
        )
    }

    internal fun medianDecimal(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]).divide(BigDecimal.valueOf(2L), 2, RoundingMode.HALF_EVEN)
        }
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
