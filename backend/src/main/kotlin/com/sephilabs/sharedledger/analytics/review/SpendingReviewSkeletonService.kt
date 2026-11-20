package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.AnalyticsService
import com.sephilabs.sharedledger.budget.BudgetService
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.catalog.CategoryView
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.getOrThrow
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val analytics: AnalyticsService,
    private val budgets: BudgetService,
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
        val selected = selectForPeriod(householdId, period, mode, today, candidatesResp, categories)
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
            ),
        )
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
            savingsOpportunities = selected.savings.map { f ->
                val txt = textById[f.id]
                AnalyticsSavingsFindingDto(
                    findingId = f.id,
                    candidateId = f.candidateId ?: f.id,
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
            savingsOmitted = selected.savingsOmitted,
            whatChangedOmitted = selected.whatChangedOmitted,
            budgetsOmitted = selected.budgetsOmitted,
            underlyingMovers = selected.underlyingMovers,
            underlyingBudgetOverruns = selected.underlyingBudgetOverruns,
        )
    }
}
