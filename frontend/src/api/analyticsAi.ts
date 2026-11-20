import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { ProjectionScenario } from "./questions";

export type AnalyticsAiRunStatus = "running" | "completed" | "failed" | "interrupted";

export interface TransactionLinkFilter {
  from: string;
  to: string;
  direction: "income" | "expense";
  categoryCode?: string | null;
  categoryGroup?: string | null;
  q?: string | null;
  unlabelled?: boolean | null;
}

export type DecisionSubjectKind = "recurring" | "merchant";
export type SpendingReviewDecisionType = "keep" | "compared" | "not_recurring" | "split";

export interface ActiveDecisionBadge {
  id: string;
  decisionType: SpendingReviewDecisionType;
  status: "active" | "snoozed" | "resurfaced" | "expired" | "inactive" | string;
  decidedBy: string;
  decidedAt: string;
  amountAtDecision?: string | null;
  snoozeUntil?: string | null;
}

export interface SpendingReviewDecision {
  id: string;
  subjectKey: string;
  subjectKind: DecisionSubjectKind;
  categoryCode: string;
  decisionType: SpendingReviewDecisionType;
  displayLabel: string;
  memberKeys: string[];
  splitGroups: string[][];
  amountAtDecision?: string | null;
  currentMonthlyCost?: string | null;
  snoozeUntil?: string | null;
  status: "active" | "snoozed" | "resurfaced" | "expired" | "inactive" | string;
  decidedBy: string;
  decidedAt: string;
}

export interface RecordSpendingReviewDecisionRequest {
  subjectKind: DecisionSubjectKind;
  categoryCode: string;
  decisionType: SpendingReviewDecisionType;
  displayLabel: string;
  memberKeys?: string[];
  splitGroups?: string[][];
  amountAtDecision?: string | null;
}

export interface UpcomingCharge {
  id: string;
  subjectKey: string;
  merchant: string;
  payloadLabel: string;
  anonymized: boolean;
  categoryCode: string;
  groupCode: string | null;
  cadence: "quarterly" | "yearly" | string;
  action: "cancellable" | "negotiable" | "none" | string;
  expectedFrom: string;
  expectedTo: string;
  typicalAmount: string;
  compareBeforeRenewal: boolean;
  kept: boolean;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface SinceLastMonthItem {
  id: string;
  changeType:
    | "stopped_recurring"
    | "new_recurring"
    | "amount_changed"
    | "decision_resurfaced"
    | "decision_expired"
    | string;
  subjectKey?: string | null;
  merchant: string;
  payloadLabel: string;
  anonymized: boolean;
  categoryCode: string;
  previousMonthlyCost?: string | null;
  currentMonthlyCost?: string | null;
  deltaMonthly?: string | null;
  cadence?: string | null;
  title: string;
  detail: string;
  txFilter?: TransactionLinkFilter | null;
}

export interface ExplorerLink {
  scopeType: "category" | "group";
  scopeCode: string;
  preset: "3m" | "6m" | "ytd" | "1y" | "2y" | "all" | "custom";
  from?: string | null;
  to?: string | null;
}

export interface SpendingReviewPeriod {
  year: number;
  month: number;
  reviewKind: "complete_month" | "pace_review" | "savings_only";
  windowFrom: string;
  windowTo: string;
  daysElapsed: number;
  daysInMonth: number;
  minDaysElapsed: number;
  paceReviewEligible: boolean;
  staleSpendingSince: string | null;
  periodHasNoExpenses: boolean;
  monthsAvailable: number;
  lastMonthWithData?: string | null;
  reviewBlockedReason?: "empty_or_stale" | "pace_too_early" | null;
}

export interface RecurringChargeCandidate {
  id: string;
  subjectKey?: string;
  memberKeys?: string[];
  merchant: string;
  mergedVariants?: string[];
  attributedEntries?: string[];
  payloadLabel: string;
  anonymized: boolean;
  categoryCode: string;
  groupCode: string | null;
  essential: boolean;
  action?: "cancellable" | "negotiable" | "none";
  cadence: "weekly" | "monthly" | "quarterly" | "yearly";
  source: "template" | "detected";
  occurrences: number;
  latestAmount: string;
  monthlyCost: string;
  annualCost: string;
  priorYearAmount: string | null;
  yoyDeltaAbs: string | null;
  yoyDeltaPct: number | null;
  yoyChangeKind?: "price_increase" | "price_decrease" | "changed" | null;
  potentialMonthlySaving: string | null;
  potentialAnnualSaving: string | null;
  lastOccurrenceDate?: string | null;
  decision?: ActiveDecisionBadge | null;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface TopMerchantCandidate {
  id: string;
  subjectKey?: string;
  memberKeys?: string[];
  merchant: string;
  mergedVariants?: string[];
  payloadLabel: string;
  anonymized: boolean;
  categoryCode: string;
  groupCode: string | null;
  essential: boolean;
  total: string;
  purchases: number;
  averageTicket: string;
  shareOfCategoryPct: number;
  monthlyEquivalent: string;
  potentialMonthlySaving: string | null;
  potentialAnnualSaving: string | null;
  decision?: ActiveDecisionBadge | null;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface OtherMerchantsAggregate {
  total: string;
  purchases: number;
  merchantCount: number;
}

export interface FeeCandidate {
  id: string;
  categoryCode: string;
  groupCode: string | null;
  essential: boolean;
  total12m: string;
  monthlyAverage: string;
  annualCost: string;
  occurrences: number;
  potentialMonthlySaving: string | null;
  potentialAnnualSaving: string | null;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface LoanInterestInfo {
  total12m: string;
  monthlyAverage: string;
  occurrences: number;
  essential: boolean;
}

export interface LumpyCategoryCandidate {
  id: string;
  categoryCode: string;
  groupCode: string | null;
  essential: boolean;
  monthlyMean: string;
  monthlyMedian: string;
  trimToMedianMonthly: string;
  trimToMedianAnnual: string;
  potentialMonthlySaving: string;
  potentialAnnualSaving: string;
  nonZeroMonths: number;
  peakMonthAmount: string;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface CutCombination {
  targetMonthly: number;
  reached: boolean;
  candidateIds: string[];
  combinedMonthlySaving: string;
  combinedAnnualSaving: string;
}

export interface SavingsCandidatesSummary {
  nonEssentialRecurringMonthly: string;
  nonEssentialRecurringAnnual: string;
  feesMonthly: string;
  feesAnnual: string;
  lumpyTrimMonthly: string;
  lumpyTrimAnnual: string;
  combinedPotentialMonthly: string;
  combinedPotentialAnnual: string;
}

export interface SavingsCandidates {
  recurringCharges: RecurringChargeCandidate[];
  topNonEssentialMerchants: TopMerchantCandidate[];
  otherMerchants: OtherMerchantsAggregate;
  feesAndInterest: {
    fees: FeeCandidate[];
    loanInterest: LoanInterestInfo | null;
  };
  lumpyCategories: LumpyCategoryCandidate[];
  oneOffCategories?: LumpyCategoryCandidate[];
  summary: SavingsCandidatesSummary;
  cutCombinations: CutCombination[];
}

export interface UnderlyingMover {
  categoryCode: string;
  groupCode: string | null;
  comparisonKind: "trailing6_avg" | "year_ago" | string;
  periodAmount: string;
  comparisonAmount: string;
  deltaAbs: string;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface UnderlyingBudgetOverrun {
  categoryCode: string;
  budget: string;
  spent: string;
  overUnder: string;
  pctConsumed: number;
  txFilter: TransactionLinkFilter;
  explorerLink: ExplorerLink;
}

export interface SpendingReviewSkeleton {
  mode: "full" | "savings_only" | "blocked";
  summary: string | null;
  sinceLastMonth?: SinceLastMonthItem[];
  savingsOpportunities: AnalyticsSavingsFinding[];
  whatChanged: AnalyticsChangeFinding[];
  budgetFindings: AnalyticsBudgetFinding[];
  upcomingCharges?: UpcomingCharge[];
  savingsOmitted: number;
  whatChangedOmitted: number;
  budgetsOmitted: number;
  underlyingMovers: UnderlyingMover[];
  underlyingBudgetOverruns: UnderlyingBudgetOverrun[];
}

export interface SpendingReviewCandidatesResponse {
  period: SpendingReviewPeriod;
  candidates: SavingsCandidates;
  decisions?: SpendingReviewDecision[];
  skeleton?: SpendingReviewSkeleton | null;
}

export interface AnalyticsAiCitation {
  path: string;
  value: unknown;
  grounded: boolean;
  context?: string | null;
}

export interface VerifiedMetric {
  key: string;
  kind: "money" | "whole_money" | "percent" | "count" | "multiple" | "text" | string;
  value: string;
}

export interface AnalyticsSavingsFinding {
  findingId?: string;
  candidateId: string;
  subjectKey?: string | null;
  subjectKind?: DecisionSubjectKind | null;
  memberKeys?: string[];
  amountAtDecision?: string | null;
  decision?: ActiveDecisionBadge | null;
  localName: string | null;
  mergedVariants?: string[];
  categoryCode: string | null;
  groupCode: string | null;
  title: string;
  detail: string;
  whyFlagged: string;
  severity: "warning" | "opportunity" | string;
  action?: "cancellable" | "negotiable" | string | null;
  potentialMonthlySaving: string | null;
  potentialAnnualSaving: string | null;
  metrics: VerifiedMetric[];
  citations: AnalyticsAiCitation[];
  txFilter: TransactionLinkFilter | null;
  explorerLink: ExplorerLink | null;
  simulationScenario: ProjectionScenario | null;
}

export interface AnalyticsChangeFinding {
  findingId?: string;
  subjectKey?: string | null;
  subjectKind?: DecisionSubjectKind | null;
  memberKeys?: string[];
  decision?: ActiveDecisionBadge | null;
  categoryCode: string | null;
  groupCode: string | null;
  localName?: string | null;
  mergedVariants?: string[];
  title: string;
  detail: string;
  changeKind: "drift" | "one_off" | "price_increase" | "drop" | string;
  severity: "critical" | "warning" | "opportunity" | string;
  metrics: VerifiedMetric[];
  citations: AnalyticsAiCitation[];
  txFilter: TransactionLinkFilter | null;
  explorerLink: ExplorerLink | null;
}

export interface AnalyticsBudgetFinding {
  findingId?: string;
  categoryCode: string | null;
  title: string;
  detail: string;
  severity: "critical" | "warning" | "opportunity" | string;
  metrics: VerifiedMetric[];
  citations: AnalyticsAiCitation[];
  txFilter: TransactionLinkFilter | null;
  explorerLink: ExplorerLink | null;
}

export interface AnalyticsDataGap {
  topic: string;
  reason: string;
  citations: AnalyticsAiCitation[];
}

export interface AnalyticsAiFollowup {
  id: string;
  question: string;
  answer: string;
  citations: AnalyticsAiCitation[];
  citationsTotal: number;
  citationsUngrounded: number;
  inputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number | null;
  createdAt: string;
}

export interface AnalyticsAiScope {
  periodYear: number;
  periodMonth: number;
  reviewKind: "complete_month" | "pace_review" | "savings_only";
  daysElapsed: number;
  daysInMonth: number;
  staleSpendingSince: string | null;
  hasBudgets: boolean;
}

export interface AnalyticsAiRun {
  id: string;
  status: AnalyticsAiRunStatus;
  createdAt: string;
  finishedAt: string | null;
  scope: AnalyticsAiScope;
  model: string;
  promptVersion: string;
  summary: string | null;
  savingsOpportunities: AnalyticsSavingsFinding[];
  whatChanged: AnalyticsChangeFinding[];
  budgetFindings: AnalyticsBudgetFinding[];
  dataGaps: AnalyticsDataGap[];
  savingsOmitted?: number;
  whatChangedOmitted?: number;
  budgetsOmitted?: number;
  modelWrittenCount?: number | null;
  templateFallbackCount?: number | null;
  findingsDropped: number | null;
  citationsTotal: number | null;
  citationsUngrounded: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  reasoningTokens: number | null;
  latencyMs: number | null;
  errorCode: string | null;
  followups: AnalyticsAiFollowup[];
}

export interface AnalyticsAiPayloadPreview {
  periodYear: number;
  periodMonth: number;
  reviewKind: "complete_month" | "pace_review" | "savings_only";
  model: string;
  providerHost: string;
  byteSize: number;
  truncatedSections: string[];
  payloadJson: string;
}

export const ANALYTICS_AI_FOLLOWUP_MAX_CHARS = 500;
const POLL_MS = 1500;
const runsKey = (householdId: string) => ["analytics-ai-review", householdId];

export function useAnalyticsAiPayloadPreview(
  householdId: string,
  year: number,
  month: number,
  enabled: boolean,
  mode?: "full" | "savings_only",
) {
  return useQuery({
    queryKey: ["analytics-ai-payload-preview", householdId, year, month, mode ?? "full"],
    queryFn: async () =>
      (
        await apiClient.get<AnalyticsAiPayloadPreview>(
          `/households/${householdId}/analytics/ai-review/payload-preview`,
          { params: mode === "savings_only" ? { year, month, mode } : { year, month } },
        )
      ).data,
    enabled,
  });
}

export function useSpendingReviewCandidates(
  householdId: string,
  year?: number,
  month?: number,
  mode?: "full" | "savings_only",
) {
  return useQuery({
    queryKey: ["analytics-spending-review-candidates", householdId, year ?? "default", month ?? "default", mode ?? "full"],
    queryFn: async () =>
      (
        await apiClient.get<SpendingReviewCandidatesResponse>(
          `/households/${householdId}/analytics/spending-review/candidates`,
          {
            params:
              year != null && month != null
                ? mode === "savings_only"
                  ? { year, month, mode }
                  : { year, month }
                : mode === "savings_only"
                  ? { mode }
                  : undefined,
          },
        )
      ).data,
  });
}

export function useAnalyticsAiRuns(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: runsKey(householdId),
    queryFn: async () =>
      (await apiClient.get<AnalyticsAiRun[]>(`/households/${householdId}/analytics/ai-review`)).data,
    enabled,
    refetchInterval: (query) => (query.state.data?.some((r) => r.status === "running") ? POLL_MS : false),
  });
}

export function useStartAnalyticsAiReview(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { year?: number; month?: number; mode?: "full" | "savings_only" }) =>
      (await apiClient.post<AnalyticsAiRun>(`/households/${householdId}/analytics/ai-review`, body)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: runsKey(householdId) }),
    meta: { silentSuccess: true, silentError: true },
  });
}

export function useAskAnalyticsAiFollowup(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ runId, question }: { runId: string; question: string }) =>
      (
        await apiClient.post<AnalyticsAiFollowup>(
          `/households/${householdId}/analytics/ai-review/${runId}/followups`,
          { question },
        )
      ).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: runsKey(householdId) }),
    meta: { silentSuccess: true, silentError: true },
  });
}

export function useDeleteAnalyticsAiRun(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (runId: string) => {
      await apiClient.delete(`/households/${householdId}/analytics/ai-review/${runId}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: runsKey(householdId) }),
    meta: { silentSuccess: true },
  });
}

export function useRecordSpendingReviewDecision(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: RecordSpendingReviewDecisionRequest) =>
      (
        await apiClient.post<SpendingReviewDecision>(
          `/households/${householdId}/analytics/spending-review/decisions`,
          body,
        )
      ).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["analytics-spending-review-candidates", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics-ai-payload-preview", householdId] });
    },
    meta: { silentSuccess: true },
  });
}

export function useDeleteSpendingReviewDecision(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (decisionId: string) => {
      await apiClient.delete(`/households/${householdId}/analytics/spending-review/decisions/${decisionId}`);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["analytics-spending-review-candidates", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics-ai-payload-preview", householdId] });
    },
    meta: { silentSuccess: true },
  });
}
