import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type ContributionMode = "manual" | "savings" | "movements";
export type SpendingBaseMode = "derived" | "manual";
export type SpendingAggregationMode = "mean" | "median";
export type FireTierKey = "lean" | "fire" | "fat" | "custom";
export type ActualReturnUnavailableReason = "insufficient_snapshots" | "no_movements" | "not_computable";
export type CashFlowEventKind = "lump_sum" | "contribution_delta" | "spending_delta";

export interface FireCashFlowEvent {
  year: number;
  kind: CashFlowEventKind;
  amount: string;
  label: string;
  /** The life-event template the event was created from; display only. */
  template?: string | null;
}

export interface ReturnScenario {
  name: string;
  meanPercent: string;
  stdDevPercent: string;
  /** Marks the "(historical — reference)" scenario derived from the household's own returns. */
  historical: boolean;
}

export interface TaxBracket {
  lowerBound: string;
  ratePct: string;
}

export interface FireSettings {
  targetAmount: string;
  targetYear: number;
  monthlyContribution: string;
  returnScenarios: ReturnScenario[];
  qualifyingAssetClasses: string[];
  expectedInflationPct: string;
  safeWithdrawalRatePct: string;
  fatMultiplier: string;
  contributionMode: ContributionMode;
  indexContribution: boolean;
  essentialSpendingMode: SpendingBaseMode;
  manualEssentialSpending: string;
  totalSpendingMode: SpendingBaseMode;
  manualTotalSpending: string;
  tierLeanEnabled: boolean;
  tierFireEnabled: boolean;
  tierFatEnabled: boolean;
  tierCustomEnabled: boolean;
  applyCapitalGainsTax: boolean;
  indexTaxBrackets: boolean;
  drawdownYears: number;
  spendingLookbackMonths: number;
  spendingAggregation: SpendingAggregationMode;
  excludeCurrentMonth: boolean;
  fallbackGainFractionPct: string;
  taxBrackets: TaxBracket[];
  cashFlowEvents: FireCashFlowEvent[];
  referenceTier: FireTierKey;
  referenceScenarioIndex: number;
}

export interface ScenarioPercentiles {
  year: number;
  p10: string;
  p25: string;
  p50: string;
  p75: string;
  p90: string;
}

export interface FireTierScenarioStats {
  tier: FireTierKey;
  deterministicHitYear: number | null;
  probabilityOfReachingTarget: number;
  /** First year by which at least half of ALL trials have reached the target; null when fewer than half do. */
  estimatedYear: number | null;
  /** Conditional median among reaching trials only — not the displayed estimate. */
  medianYearAmongReaching: number | null;
  coastProbabilityOfReachingTarget: number;
  coastEstimatedYear: number | null;
  coastMedianYearAmongReaching: number | null;
  coastTargetToday: string | null;
  requiredMonthlyToHitTarget: string | null;
  drawdownSurvivalRate: number | null;
  drawdownPercentiles: ScenarioPercentiles[];
  drawdownRealPercentiles?: ScenarioPercentiles[];
}

export interface FirePreviewRequest {
  monthlyContribution?: string | null;
  targetYear?: number | null;
  safeWithdrawalRatePct?: string | null;
  lumpSum?: string;
  monthlySpending?: string | null;
  monthlyEssentialSpending?: string | null;
  returnShiftPct?: string | null;
  cashFlowEvents?: FireCashFlowEvent[] | null;
  /** The unsaved settings form, projected in place of the saved settings. */
  settings?: FireSettings | null;
}

export interface FireScenarioOutput {
  name: string;
  meanPercent: string;
  stdDevPercent: string;
  historical: boolean;
  series: { year: number; value: string }[];
  percentiles: ScenarioPercentiles[];
  tierStats: FireTierScenarioStats[];
}

export interface FireTierOutput {
  key: FireTierKey;
  enabled: boolean;
  monthlyNetSpending: string | null;
  annualNetSpending: string | null;
  annualGrossSpending: string | null;
  estimatedAnnualTax: string | null;
  targetToday: string | null;
  coveragePercent: number | null;
  targetCurve: { year: number; value: string }[];
}

/** essentialMonthly/totalMonthly are the EFFECTIVE bases (manual override when active); the derived values are always carried for display. */
export interface FireSpendingBasis {
  essentialMonthly: string;
  totalMonthly: string;
  derivedEssentialMonthly: string;
  derivedTotalMonthly: string;
  essentialMode: SpendingBaseMode;
  totalMode: SpendingBaseMode;
  monthsAvailable: number;
  lookbackMonths: number;
  aggregation: SpendingAggregationMode;
  excludeCurrentMonth: boolean;
  /** "YYYY-MM" of the last month with any expense when the window's latest complete months have none. */
  noSpendingSince: string | null;
}

export interface FireContributions {
  manualMonthly: string;
  savingsMonthly: string | null;
  savingsRatePct: number | null;
  movementsMonthly: string | null;
  mode: ContributionMode;
  activeMonthly: string | null;
}

export interface FireActualReturn {
  annualizedPercent: string;
  fromDate: string;
  toDate: string;
  movementCount: number;
  firstMovementDate: string;
  /** Whole months of history before the first recorded movement; > 0 means coverage is partial. */
  uncoveredMonths: number;
}

export interface FireHistoricalScenario {
  meanPercent: string;
  stdDevPercent: string;
  yearsOfData: number;
}

export interface FireGainFraction {
  percent: string;
  source: "movements" | "manual";
  uncoveredMonths: number;
}

export interface FireProjection {
  startYear: number;
  startingValue: string;
  snapshotDate: string | null;
  settings: FireSettings;
  spending: FireSpendingBasis;
  contributions: FireContributions;
  gainFraction: FireGainFraction;
  actualReturn: FireActualReturn | null;
  actualReturnUnavailableReason: ActualReturnUnavailableReason | null;
  historicalScenario: FireHistoricalScenario | null;
  suggestedScenario: ReturnScenario | null;
  tiers: FireTierOutput[];
  scenarios: FireScenarioOutput[];
  historicalWealth: { year: number; value: string }[];
  cumulativeContributions: { year: number; cumulative: string }[];
  monteCarloTrials: number;
}

export function useFireSettings(householdId: string) {
  return useQuery({
    queryKey: ["fire-settings", householdId],
    queryFn: async () => (await apiClient.get<FireSettings>(`/households/${householdId}/fire/settings`)).data,
  });
}

export function useUpdateFireSettings(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: FireSettings) =>
      (await apiClient.put<FireSettings>(`/households/${householdId}/fire/settings`, input)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["fire-settings", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-history", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-attribution", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-levers", householdId] });
    },
  });
}

export function useFireProjection(householdId: string) {
  return useQuery({
    queryKey: ["fire-projection", householdId],
    queryFn: async () => (await apiClient.get<FireProjection>(`/households/${householdId}/fire/projection`)).data,
  });
}

export function useFirePreview(householdId: string) {
  return useMutation({
    mutationFn: async (input: FirePreviewRequest) =>
      (await apiClient.post<FireProjection>(`/households/${householdId}/fire/preview`, input)).data,
    // A what-if saves nothing, so it must not say "Saved".
    meta: { silentSuccess: true },
  });
}

/** The projection the unsaved settings form would have once saved; keeps the last answer while the next one loads. */
export function useFireFormPreview(householdId: string, form: FireSettings, enabled: boolean) {
  return useQuery({
    queryKey: ["fire-form-preview", householdId, form],
    queryFn: async () =>
      (await apiClient.post<FireProjection>(`/households/${householdId}/fire/preview`, { settings: form })).data,
    enabled,
    placeholderData: keepPreviousData,
  });
}

export type FireLeverKey = "contribute_more" | "spend_less" | "lump_sum" | "safer_withdrawal" | "returns_up" | "returns_down";

export interface FireLeverRow {
  key: FireLeverKey;
  outOfYourHands: boolean;
  /** False when the lever cannot change the reference target (spending levers on an own target). */
  applicable: boolean;
  /** The what-if to load when the row is opened. */
  preview: FirePreviewRequest | null;
  estimatedYear: number | null;
  probability: number | null;
  yearChange: number | null;
  probabilityChangePoints: number | null;
  amount: string | null;
  percent: string | null;
  withdrawalFromPct: string | null;
  withdrawalToPct: string | null;
}

export interface FireLevers {
  tier: FireTierKey;
  horizonYear: number;
  baselineYear: number | null;
  baselineProbability: number | null;
  rows: FireLeverRow[];
}

/** Six sandbox runs, fetched in the background once the photo is on screen; cached by the backend. */
export function useFireLevers(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["fire-levers", householdId],
    queryFn: async () => (await apiClient.get<FireLevers>(`/households/${householdId}/fire/levers`)).data,
    enabled,
  });
}

export interface FireTemplateSuggestions {
  mortgageMonthly: string | null;
  windowMonths: number;
}

export function useFireTemplateSuggestions(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["fire-template-suggestions", householdId],
    queryFn: async () =>
      (await apiClient.get<FireTemplateSuggestions>(`/households/${householdId}/fire/templates/suggestions`)).data,
    enabled,
  });
}

export type FireHistoryOmissionReason = "insufficient_spending_history" | "no_target" | "beyond_point_limit";

/** The photo as if looked at on `date`: that day's wealth and spending window, today's settings. */
export interface FireHistoryPoint {
  date: string;
  snapshotDate: string;
  current: boolean;
  coveragePercent: number;
  estimatedYear: number | null;
  probability: number | null;
  wealth: string;
  target: string;
}

/** A change in coverage (percentage points) split into causes that add up exactly. */
export interface FireAttribution {
  fromDate: string;
  toDate: string;
  merged: boolean;
  totalPoints: string;
  contributedPoints: string | null;
  marketPoints: string | null;
  wealthPoints: string;
  needPoints: string;
  wealthFrom: string;
  wealthTo: string;
  targetFrom: string;
  targetTo: string;
  contributedAmount: string | null;
  marketAmount: string | null;
}

export interface FireHistory {
  tier: FireTierKey;
  horizonYear: number;
  points: FireHistoryPoint[];
  omitted: { reason: FireHistoryOmissionReason; count: number }[];
  manualContribution: boolean;
  manualSpending: boolean;
  attribution: FireAttribution | null;
}

/** Computed after the photo, in the background; the backend caches it per settings and data. */
export function useFireHistory(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["fire-history", householdId],
    queryFn: async () => (await apiClient.get<FireHistory>(`/households/${householdId}/fire/history`)).data,
    enabled,
  });
}

export function useFireAttribution(householdId: string, from: string | null) {
  return useQuery({
    queryKey: ["fire-attribution", householdId, from],
    queryFn: async () =>
      (await apiClient.get<FireAttribution>(`/households/${householdId}/fire/history/attribution`, { params: { from } })).data,
    enabled: from !== null,
  });
}
