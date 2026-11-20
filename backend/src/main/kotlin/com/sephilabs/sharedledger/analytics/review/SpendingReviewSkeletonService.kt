package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.AnalyticsService
import com.sephilabs.sharedledger.budget.BudgetService
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.catalog.CategoryView
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.getOrThrow
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

/** Single deterministic assembly point behind both the local report skeleton (`GET /spending-review/candidates`)
 *  and the AI review payload (`GET /ai-review/payload-preview`, `POST /ai-review`). */
@Service
class SpendingReviewSkeletonService(
    private val savingsCandidates: SavingsCandidatesService,
    private val decisionService: SpendingReviewDecisionService,
    private val analytics: AnalyticsService,
    private val budgets: BudgetService,
    private val dataQueries: SpendingReviewDataQueries,
    private val categoryService: CategoryService,
    private val households: HouseholdRepository,
    private val messageSource: MessageSource,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun candidatesWithSkeleton(
        householdId: UUID,
        year: Int? = null,
        month: Int? = null,
        modeParam: String? = null,
        locale: String = "en",
    ): SpendingReviewCandidatesResponse {
        val today = LocalDate.now(clock)
        val period = savingsCandidates.resolvePeriod(year, month, today)
        val candidatesResp = savingsCandidates.candidates(householdId, period.year, period.monthValue)
        val categories = categoryService.listForHousehold(householdId)
        val labelFn = categoryLabelResolver(categories, locale)

        val wantsSavingsOnly = modeParam.equals("savings_only", ignoreCase = true)
        val blocked = candidatesResp.period.reviewBlockedReason != null && !wantsSavingsOnly
        if (blocked) {
            return candidatesResp.copy(
                skeleton = SpendingReviewSkeletonDto(
                    mode = "blocked",
                    summary = null,
                    savingsOpportunities = emptyList(),
                    whatChanged = emptyList(),
                    budgetFindings = emptyList(),
                ),
            )
        }

        val mode = if (wantsSavingsOnly) ReviewMode.SAVINGS_ONLY else ReviewMode.FULL
        val selected = selectForPeriod(householdId, period, mode, today, candidatesResp, categories, locale)
        val rendered = SpendingReviewRenderer.renderDeterministic(selected, locale, labelFn)
        val skeleton = toSkeletonDto(selected, rendered)
        return candidatesResp.copy(skeleton = skeleton)
    }

    @Transactional(readOnly = true)
    fun selectForPeriod(
        householdId: UUID,
        period: YearMonth,
        mode: ReviewMode,
        today: LocalDate,
        candidatesResp: SpendingReviewCandidatesResponse = savingsCandidates.candidates(householdId, period.year, period.monthValue),
        categories: List<CategoryView> = categoryService.listForHousehold(householdId),
        locale: String = "en",
    ): SelectedReview {
        val currency = households.getOrThrow(householdId).currency
        val isPace = candidatesResp.period.reviewKind == "pace_review"

        val monthDashboard = analytics.monthDashboard(householdId, period.year, period.monthValue)
        val trailing12 = analytics.trailing12(householdId, period)
        val moversTrailing6 = analytics.topMovers(householdId, period.year, period.monthValue, "trailing6_avg")
        val moversYearAgo = analytics.topMovers(householdId, period.year, period.monthValue, "year_ago")
        val increaseCodes = (moversTrailing6.increases.take(SpendingReviewConstants.MAX_MOVERS_PER_LIST) +
            moversYearAgo.increases.take(SpendingReviewConstants.MAX_MOVERS_PER_LIST))
            .map { it.categoryCode }
            .toSet()
        val moverBreakdowns = if (mode == ReviewMode.FULL) {
            savingsCandidates.moverBreakdowns(
                householdId = householdId,
                period = period,
                today = today,
                increaseCategoryCodes = increaseCodes,
                recurringCandidates = candidatesResp.candidates.recurringCharges,
            )
        } else emptyMap()
        val budgetSummary = if (mode == ReviewMode.FULL) {
            budgets.monthSummary(householdId, period.year.toShort(), period.monthValue.toShort())
        } else null
        val paceProjection = if (mode == ReviewMode.FULL && isPace) {
            analytics.moneyFlowProjection(householdId, today, level = "category", windowMonths = 3)
        } else null

        val incomeMovers = if (mode == ReviewMode.FULL) {
            computeIncomeMovers(
                householdId = householdId,
                period = period,
                windowFrom = candidatesResp.period.windowFrom,
                windowTo = candidatesResp.period.windowTo,
                monthsAvailable = candidatesResp.period.monthsAvailable,
                categories = categories,
            )
        } else emptyList()

        val budgetHistoryStats = if (mode == ReviewMode.FULL && budgetSummary != null) {
            val overrunRows = budgetSummary.rows.filter { it.budget.signum() > 0 && it.spent > it.budget }
            if (overrunRows.isNotEmpty()) {
                val windowMonthsCount = candidatesResp.period.monthsAvailable.coerceAtLeast(1)
                val windowMonths = (0 until windowMonthsCount).map { period.minusMonths(it.toLong()) }.reversed()
                val prior6Months = (1..6).map { period.minusMonths(it.toLong()) }.toSet()
                val queryFrom = minOf(candidatesResp.period.windowFrom, period.minusMonths(6).atDay(1))
                val queryTo = candidatesResp.period.windowTo
                val monthlyByCat = dataQueries.expenseMonthlyTotalsByCategory(
                    householdId,
                    queryFrom,
                    queryTo,
                    overrunRows.map { it.categoryCode },
                )
                val six = BigDecimal.valueOf(6L)
                overrunRows.associate { row ->
                    val byYm = monthlyByCat[row.categoryCode].orEmpty()
                    val t6Total = prior6Months.fold(BigDecimal.ZERO) { a, ym -> a + (byYm[ym] ?: BigDecimal.ZERO).max(BigDecimal.ZERO) }
                    val t6Avg = Money.normalize(t6Total.divide(six, 2, RoundingMode.HALF_EVEN))
                    val series12 = windowMonths.map { ym -> (byYm[ym] ?: BigDecimal.ZERO).max(BigDecimal.ZERO) }
                    val med12 = Money.normalize(SpendingReviewSelector.medianDecimal(series12))
                    val tot12 = Money.normalize(series12.fold(BigDecimal.ZERO) { a, v -> a + v })
                    val exceeded = series12.filter { it > row.budget }
                    val exceededTotal = Money.normalize(exceeded.fold(BigDecimal.ZERO) { a, v -> a + v })
                    row.categoryCode to BudgetHistoryStats(
                        trailing6mAvg = t6Avg,
                        median12m = med12,
                        total12m = tot12,
                        exceededMonths12m = exceeded.size,
                        exceededMonthsTotal12m = exceededTotal,
                        windowMonths = windowMonths.size,
                    )
                }
            } else emptyMap()
        } else emptyMap()

        val upcomingCharges = UpcomingChargesBuilder.build(
            recurringCharges = candidatesResp.candidates.recurringCharges,
            asOf = candidatesResp.period.windowTo,
        )

        val sinceLastMonth = if (mode == ReviewMode.FULL && candidatesResp.period.monthsAvailable >= 2) {
            val prevPeriod = period.minusMonths(1)
            val prevAsOf = prevPeriod.atEndOfMonth()
            val prevResp = savingsCandidates.computeForAsOf(householdId, prevPeriod, prevAsOf)
            val decodedDecisions = decisionService.listDecoded(householdId)
            SinceLastMonthDiff.compute(
                current = candidatesResp.candidates,
                previous = prevResp.candidates,
                decisions = decodedDecisions,
                asOfCurrent = candidatesResp.period.windowTo,
                asOfPrevious = prevAsOf,
                currency = currency,
                locale = locale,
            )
        } else emptyList()

        return SpendingReviewSelector.select(
            SpendingReviewSelector.Inputs(
                currency = currency,
                mode = mode,
                candidatesResponse = candidatesResp,
                monthDashboard = monthDashboard,
                trailing12 = trailing12,
                moversVsTrailing6 = moversTrailing6,
                moversVsYearAgo = moversYearAgo,
                moverBreakdowns = moverBreakdowns,
                budgetSummary = budgetSummary,
                paceProjection = paceProjection,
                categories = categories,
                incomeMovers = incomeMovers,
                budgetHistoryStats = budgetHistoryStats,
                upcomingCharges = upcomingCharges,
                sinceLastMonth = sinceLastMonth,
            ),
        )
    }

    private fun computeIncomeMovers(
        householdId: UUID,
        period: YearMonth,
        windowFrom: LocalDate,
        windowTo: LocalDate,
        monthsAvailable: Int,
        categories: List<CategoryView>,
    ): List<IncomeCategoryMover> {
        if (monthsAvailable <= 0) return emptyList()
        val windowMonths = (0 until monthsAvailable).map { period.minusMonths(it.toLong()) }.reversed()
        val monthlyByCat = dataQueries.incomeMonthlyTotalsByCategory(householdId, windowFrom, windowTo)
        if (monthlyByCat.isEmpty()) return emptyList()
        val catByCode = categories.associateBy { it.code }
        val denom = BigDecimal.valueOf(windowMonths.size.toLong())

        return monthlyByCat.map { (catCode, byYm) ->
            val series = windowMonths.map { ym -> byYm[ym] ?: BigDecimal.ZERO }
            val periodAmt = Money.normalize(byYm[period] ?: BigDecimal.ZERO)
            val total = series.fold(BigDecimal.ZERO) { a, v -> a + v }
            val avg = Money.normalize(total.divide(denom, 2, RoundingMode.HALF_EVEN))
            val med = Money.normalize(SpendingReviewSelector.medianDecimal(series))
            IncomeCategoryMover(
                categoryCode = catCode,
                groupCode = catByCode[catCode]?.group,
                periodAmount = periodAmt,
                avg12m = avg,
                median12m = med,
            )
        }
    }

    fun categoryLabelResolver(categories: List<CategoryView>, locale: String): (String) -> String {
        val javaLocale = if (locale.startsWith("es", ignoreCase = true)) Locale.forLanguageTag("es-ES") else Locale.ENGLISH
        val byCode = categories.associateBy { it.code }
        return { code ->
            val cat = byCode[code]
            if (cat != null && cat.custom) {
                cat.name
            } else {
                val key = "category.$code"
                messageSource.getMessage(key, null, null, javaLocale)
                    ?: cat?.name?.removePrefix("category.")
                    ?: code.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
            }
        }
    }

    fun toSkeletonDto(selected: SelectedReview, rendered: RenderedReview): SpendingReviewSkeletonDto {
        val textById = rendered.findings.associateBy { it.findingId }
        return SpendingReviewSkeletonDto(
            mode = if (selected.mode == ReviewMode.SAVINGS_ONLY) "savings_only" else "full",
            summary = rendered.summary,
            sinceLastMonth = selected.sinceLastMonth,
            savingsOpportunities = selected.savings.map { f ->
                val txt = textById[f.id]
                AnalyticsSavingsFindingDto(
                    findingId = f.id,
                    candidateId = f.candidateId ?: f.id,
                    subjectKey = f.subjectKey,
                    subjectKind = f.subjectKind,
                    memberKeys = f.memberKeys,
                    amountAtDecision = f.amountAtDecision,
                    decision = f.decision,
                    localName = f.localSubject,
                    mergedVariants = f.mergedVariants,
                    categoryCode = f.categoryCode,
                    groupCode = f.groupCode,
                    title = txt?.title.orEmpty(),
                    detail = txt?.detail.orEmpty(),
                    whyFlagged = "",
                    severity = f.severity,
                    action = f.action?.name,
                    potentialMonthlySaving = f.potentialMonthlySaving,
                    potentialAnnualSaving = f.potentialAnnualSaving,
                    metrics = f.figures.map { VerifiedMetricDto(it.role, it.kind.name.lowercase(), it.value.stripTrailingZeros().toPlainString()) },
                    citations = emptyList(),
                    txFilter = f.txFilter,
                    explorerLink = f.explorerLink,
                    simulationScenario = f.simulationScenario?.toScenarioDto(),
                )
            },
            whatChanged = selected.whatChanged.map { f ->
                val txt = textById[f.id]
                AnalyticsChangeFindingDto(
                    findingId = f.id,
                    subjectKey = f.subjectKey,
                    subjectKind = f.subjectKind,
                    memberKeys = f.memberKeys,
                    decision = f.decision,
                    categoryCode = f.categoryCode,
                    groupCode = f.groupCode,
                    localName = f.localSubject,
                    mergedVariants = f.mergedVariants,
                    title = txt?.title.orEmpty(),
                    detail = txt?.detail.orEmpty(),
                    changeKind = f.changeKind ?: "drift",
                    severity = f.severity,
                    metrics = f.figures.map { VerifiedMetricDto(it.role, it.kind.name.lowercase(), it.value.stripTrailingZeros().toPlainString()) },
                    citations = emptyList(),
                    txFilter = f.txFilter,
                    explorerLink = f.explorerLink,
                )
            },
            budgetFindings = selected.budgets.map { f ->
                val txt = textById[f.id]
                AnalyticsBudgetFindingDto(
                    findingId = f.id,
                    categoryCode = f.categoryCode,
                    title = txt?.title.orEmpty(),
                    detail = txt?.detail.orEmpty(),
                    severity = f.severity,
                    metrics = f.figures.map { VerifiedMetricDto(it.role, it.kind.name.lowercase(), it.value.stripTrailingZeros().toPlainString()) },
                    citations = emptyList(),
                    txFilter = f.txFilter,
                    explorerLink = f.explorerLink,
                )
            },
            upcomingCharges = selected.upcomingCharges,
            savingsOmitted = selected.savingsOmitted,
            whatChangedOmitted = selected.whatChangedOmitted,
            budgetsOmitted = selected.budgetsOmitted,
            underlyingMovers = selected.underlyingMovers,
            underlyingBudgetOverruns = selected.underlyingBudgetOverruns,
        )
    }
}
