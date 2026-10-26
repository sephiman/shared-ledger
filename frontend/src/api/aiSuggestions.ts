import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import { useInstanceFeatures } from "./features";

export interface AiSuggestionSettings {
  enabled: boolean;
  /** Where the data goes, named in the setting's help text and the button's tooltip. */
  model: string;
  providerHost: string;
}

export type AiSuggestionRunStatus = "running" | "completed" | "failed" | "interrupted";

export interface AiSuggestionRun {
  id: string;
  status: AiSuggestionRunStatus;
  batchesTotal: number;
  batchesDone: number;
  batchesFailed: number;
  itemsSent: number;
  suggestionsStored: number;
  itemsUnsuggested: number;
  startedAt: string;
  finishedAt: string | null;
}

export interface AiSuggestionStatus {
  /** Pending items with no suggestion of any origin: what a press would send. */
  candidates: number;
  run: AiSuggestionRun | null;
}

const settingsKey = (householdId: string) => ["ai-suggestion-settings", householdId];

/** Only requested once the instance reports the feature; otherwise nothing AI-related is fetched. */
export function useAiSuggestionSettings(householdId: string) {
  const { data: features } = useInstanceFeatures();
  return useQuery({
    queryKey: settingsKey(householdId),
    queryFn: async () =>
      (await apiClient.get<AiSuggestionSettings>(`/households/${householdId}/ai-suggestions/settings`)).data,
    enabled: features?.aiCategorySuggestions === true,
  });
}

/** The inbox shows the button, badge and filter only for a household that turned the setting on. */
export function useAiSuggestionsOn(householdId: string): AiSuggestionSettings | null {
  const { data } = useAiSuggestionSettings(householdId);
  return data?.enabled ? data : null;
}

export function useUpdateAiSuggestionSettings(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (enabled: boolean) =>
      (await apiClient.put<AiSuggestionSettings>(`/households/${householdId}/ai-suggestions/settings`, { enabled })).data,
    onSuccess: (data) => qc.setQueryData(settingsKey(householdId), data),
    meta: { silentSuccess: true },
  });
}

const POLL_MS = 1000;

/** Under the "banks" key so every inbox mutation refreshes the candidate count with it. Polls while a run is
 *  in progress — also one another member started. */
export function useAiSuggestionStatus(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["banks", householdId, "ai-suggestions"],
    queryFn: async () =>
      (await apiClient.get<AiSuggestionStatus>(`/households/${householdId}/banks/pending/ai-suggestions`)).data,
    enabled,
    refetchInterval: (query) => (query.state.data?.run?.status === "running" ? POLL_MS : false),
  });
}

export function useStartAiSuggestions(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () =>
      (await apiClient.post<AiSuggestionRun>(`/households/${householdId}/banks/pending/ai-suggestions`)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId] }),
    meta: { silentSuccess: true },
  });
}
