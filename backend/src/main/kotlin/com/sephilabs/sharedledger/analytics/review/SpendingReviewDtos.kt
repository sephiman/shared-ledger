package com.sephilabs.sharedledger.analytics.review

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.ai.AiCitationDto
import com.sephilabs.sharedledger.ai.AiRunStatus
import com.sephilabs.sharedledger.analytics.question.ProjectionScenarioDto
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --- Shared filter & link types --------------------------------------------

data class TransactionLinkFilterDto(
    val from: LocalDate,
    val to: LocalDate,
    val direction: String = "expense",
    val categoryCode: String? = null,
    val categoryGroup: String? = null,
    val q: String? = null,
)

data class ExplorerLinkDto(
    val scopeType: String, // "category" | "group"
    val scopeCode: String,
    val preset: String = "1y",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

// --- Deterministic savings candidates endpoint DTOs -------------------------

data class SpendingReviewPeriodDto(
    val year: Int,
    val month: Int,
    val reviewKind: String, // "complete_month" | "pace_review" | "savings_only"
    val windowFrom: LocalDate,
    val windowTo: LocalDate,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val minDaysElapsed: Int,
    val paceReviewEligible: Boolean,
    val staleSpendingSince: String?, // YYYY-MM or null
    val periodHasNoExpenses: Boolean,
    val monthsAvailable: Int,
    val lastMonthWithData: String? = null, // YYYY-MM of latest month with non-template expenses
    val reviewBlockedReason: String? = null, // "empty_or_stale" | "pace_too_early" | null
)

data class RecurringChargeCandidateDto(
    val id: String,
    val merchant: String, // real local name (UI only)
    val mergedVariants: List<String> = emptyList(), // local-only merged payee variants (never sent to AI)
    val payloadLabel: String, // sanitized name or anonymized category label
    val anonymized: Boolean,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    val action: RecurringAction = RecurringAction.cancellable,
    val cadence: DetectedCadence,
    val source: RecurringSource,
    val occurrences: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val latestAmount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyCost: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualCost: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val priorYearAmount: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val yoyDeltaAbs: BigDecimal?,
    val yoyDeltaPct: Double?,
    val yoyChangeKind: YoyChangeKind? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialMonthlySaving: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialAnnualSaving: BigDecimal?,
    val txFilter: TransactionLinkFilterDto,
    val explorerLink: ExplorerLinkDto,
)

data class TopMerchantCandidateDto(
    val id: String,
    val merchant: String, // real local name (UI only)
    val mergedVariants: List<String> = emptyList(), // local-only merged payee variants (never sent to AI)
    val payloadLabel: String,
    val anonymized: Boolean,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val total: BigDecimal,
    val purchases: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val averageTicket: BigDecimal,
    val shareOfCategoryPct: Double,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyEquivalent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialMonthlySaving: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialAnnualSaving: BigDecimal?,
    val txFilter: TransactionLinkFilterDto,
    val explorerLink: ExplorerLinkDto,
)

data class OtherMerchantsAggregateDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val total: BigDecimal,
    val purchases: Int,
    val merchantCount: Int,
)

data class FeeCandidateDto(
    val id: String,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val total12m: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyAverage: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualCost: BigDecimal,
    val occurrences: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialMonthlySaving: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialAnnualSaving: BigDecimal?,
    val txFilter: TransactionLinkFilterDto,
    val explorerLink: ExplorerLinkDto,
)

data class LoanInterestInfoDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val total12m: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyAverage: BigDecimal,
    val occurrences: Int,
    val essential: Boolean = true,
)

data class FeesAndInterestDto(
    val fees: List<FeeCandidateDto>,
    val loanInterest: LoanInterestInfoDto?,
)

data class LumpyCategoryCandidateDto(
    val id: String,
    val categoryCode: String,
    val groupCode: String?,
    val essential: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyMean: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyMedian: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val trimToMedianMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val trimToMedianAnnual: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialMonthlySaving: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialAnnualSaving: BigDecimal,
    val nonZeroMonths: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val peakMonthAmount: BigDecimal,
    val txFilter: TransactionLinkFilterDto,
    val explorerLink: ExplorerLinkDto,
)

data class CutCombinationDto(
    val targetMonthly: Int,
    val reached: Boolean,
    val candidateIds: List<String>,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val combinedMonthlySaving: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val combinedAnnualSaving: BigDecimal,
)

data class SavingsCandidatesSummaryDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val nonEssentialRecurringMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val nonEssentialRecurringAnnual: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val feesMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val feesAnnual: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val lumpyTrimMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val lumpyTrimAnnual: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val combinedPotentialMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val combinedPotentialAnnual: BigDecimal,
)

data class SavingsCandidatesDto(
    val recurringCharges: List<RecurringChargeCandidateDto>,
    val topNonEssentialMerchants: List<TopMerchantCandidateDto>,
    val otherMerchants: OtherMerchantsAggregateDto,
    val feesAndInterest: FeesAndInterestDto,
    val lumpyCategories: List<LumpyCategoryCandidateDto>,
    val oneOffCategories: List<LumpyCategoryCandidateDto> = emptyList(),
    val summary: SavingsCandidatesSummaryDto,
    val cutCombinations: List<CutCombinationDto>,
)

data class UnderlyingMoverDto(
    val categoryCode: String,
    val groupCode: String?,
    val comparisonKind: String, // "trailing6_avg" | "year_ago"
    @JsonFormat(shape = JsonFormat.Shape.STRING) val periodAmount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val comparisonAmount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val deltaAbs: BigDecimal,
    val txFilter: TransactionLinkFilterDto,
    val explorerLink: ExplorerLinkDto,
)

data class UnderlyingBudgetOverrunDto(
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val budget: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val spent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val overUnder: BigDecimal,
    val pctConsumed: Double,
    val txFilter: TransactionLinkFilterDto,
    val explorerLink: ExplorerLinkDto,
)

data class SpendingReviewSkeletonDto(
    val mode: String, // "full" | "savings_only" | "blocked"
    val summary: String?,
    val savingsOpportunities: List<AnalyticsSavingsFindingDto>,
    val whatChanged: List<AnalyticsChangeFindingDto>,
    val budgetFindings: List<AnalyticsBudgetFindingDto>,
    val savingsOmitted: Int = 0,
    val whatChangedOmitted: Int = 0,
    val budgetsOmitted: Int = 0,
    val underlyingMovers: List<UnderlyingMoverDto> = emptyList(),
    val underlyingBudgetOverruns: List<UnderlyingBudgetOverrunDto> = emptyList(),
)

data class SpendingReviewCandidatesResponse(
    val period: SpendingReviewPeriodDto,
    val candidates: SavingsCandidatesDto,
    val skeleton: SpendingReviewSkeletonDto? = null,
)

// --- AI Review persisted runs & report DTOs ---------------------------------

data class StartAnalyticsAiReviewRequest(
    val year: Int? = null,
    val month: Int? = null,
    val mode: String? = null, // "full" (default) | "savings_only"
)

data class AnalyticsAiPayloadPreviewDto(
    val periodYear: Int,
    val periodMonth: Int,
    val reviewKind: String,
    val model: String,
    val providerHost: String,
    val byteSize: Int,
    val truncatedSections: List<String>,
    val payloadJson: String,
)

data class AnalyticsAiFollowupRequest(
    val question: String?,
)

data class VerifiedMetricDto(
    val key: String,
    val kind: String, // "money" | "percent" | "count" | "multiple" | "text"
    val value: String,
)

data class AnalyticsSavingsFindingDto(
    val findingId: String = "",
    val candidateId: String,
    val localName: String?,
    val mergedVariants: List<String> = emptyList(),
    val categoryCode: String?,
    val groupCode: String?,
    val title: String,
    val detail: String,
    val whyFlagged: String,
    val severity: String, // "warning" | "opportunity"
    val action: String? = null, // "cancellable" | "negotiable"
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialMonthlySaving: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val potentialAnnualSaving: BigDecimal?,
    val metrics: List<VerifiedMetricDto>,
    val citations: List<AiCitationDto>,
    val txFilter: TransactionLinkFilterDto?,
    val explorerLink: ExplorerLinkDto?,
    val simulationScenario: ProjectionScenarioDto?,
)

data class AnalyticsChangeFindingDto(
    val findingId: String = "",
    val categoryCode: String?,
    val groupCode: String?,
    val localName: String? = null,
    val mergedVariants: List<String> = emptyList(),
    val title: String,
    val detail: String,
    val changeKind: String, // "drift" | "one_off" | "price_increase" | "drop"
    val severity: String, // "critical" | "warning" | "opportunity"
    val metrics: List<VerifiedMetricDto>,
    val citations: List<AiCitationDto>,
    val txFilter: TransactionLinkFilterDto?,
    val explorerLink: ExplorerLinkDto?,
)

data class AnalyticsBudgetFindingDto(
    val findingId: String = "",
    val categoryCode: String?,
    val title: String,
    val detail: String,
    val severity: String, // "critical" | "warning" | "opportunity"
    val metrics: List<VerifiedMetricDto>,
    val citations: List<AiCitationDto>,
    val txFilter: TransactionLinkFilterDto?,
    val explorerLink: ExplorerLinkDto?,
)

data class AnalyticsDataGapDto(
    val topic: String,
    val reason: String,
    val citations: List<AiCitationDto>,
)

data class AnalyticsAiFollowupDto(
    val id: UUID,
    val question: String,
    val answer: String,
    val citations: List<AiCitationDto>,
    val citationsTotal: Int,
    val citationsUngrounded: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    val createdAt: Instant,
)

data class AnalyticsAiScopeDto(
    val periodYear: Int,
    val periodMonth: Int,
    val reviewKind: String,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val staleSpendingSince: String?,
    val hasBudgets: Boolean,
)

data class AnalyticsAiRunDto(
    val id: UUID,
    val status: AiRunStatus,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val scope: AnalyticsAiScopeDto,
    val model: String,
    val promptVersion: String,
    val summary: String?,
    val savingsOpportunities: List<AnalyticsSavingsFindingDto>,
    val whatChanged: List<AnalyticsChangeFindingDto>,
    val budgetFindings: List<AnalyticsBudgetFindingDto>,
    val dataGaps: List<AnalyticsDataGapDto>,
    val savingsOmitted: Int = 0,
    val whatChangedOmitted: Int = 0,
    val budgetsOmitted: Int = 0,
    val modelWrittenCount: Int? = null,
    val templateFallbackCount: Int? = null,
    val findingsDropped: Int?,
    val citationsTotal: Int?,
    val citationsUngrounded: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val latencyMs: Long?,
    val errorCode: String?,
    val followups: List<AnalyticsAiFollowupDto> = emptyList(),
)
