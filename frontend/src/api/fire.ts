import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface ReturnScenario {
  meanPercent: string;
  stdDevPercent: string;
}

export interface FireSettings {
  targetAmount: string;
  targetYear: number;
  monthlyContribution: string;
  returnScenarios: ReturnScenario[];
  qualifyingAssetClasses: string[];
}

export interface ScenarioPercentiles {
  year: number;
  p10: string;
  p25: string;
  p50: string;
  p75: string;
  p90: string;
}

export interface FireScenarioOutput {
  meanPercent: string;
  stdDevPercent: string;
  series: { year: number; value: string }[];
  targetHitYear: number | null;
  percentiles: ScenarioPercentiles[];
  probabilityOfReachingTarget: number;
  medianYearReachingTarget: number | null;
}

export interface FireProjection {
  startYear: number;
  startingValue: string;
  settings: FireSettings;
  scenarios: FireScenarioOutput[];
  actualAnnualizedReturnPercent: string | null;
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
