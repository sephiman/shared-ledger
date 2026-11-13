import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
  });
}
