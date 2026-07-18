import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type ContributionMode = "manual" | "savings" | "movements";
export type SpendingBaseMode = "derived" | "manual";
export type FireTierKey = "lean" | "fire" | "fat" | "custom";
export type ActualReturnUnavailableReason = "insufficient_snapshots" | "no_movements" | "not_computable";

export interface ReturnScenario {
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
  fallbackGainFractionPct: string;
  taxBrackets: TaxBracket[];
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
  medianYearReachingTarget: number | null;
  coastProbabilityOfReachingTarget: number;
  coastMedianYearReachingTarget: number | null;
}

export interface FireScenarioOutput {
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
}

export interface FireContributions {
  manualMonthly: string;
  savingsMonthly: string | null;
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
  tiers: FireTierOutput[];
  scenarios: FireScenarioOutput[];
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
