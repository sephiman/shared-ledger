import { useMutation, useQuery } from "@tanstack/react-query";
import type { RangePreset } from "@/lib/range";
import { apiClient } from "./client";

/** The server refuses longer questions. */
export const QUESTION_MAX_CHARS = 300;

export type QuestionTarget = "explorer" | "transactions";

export interface QuestionScope {
  type: "group" | "category";
  code: string;
}

/** The range selector's value: a preset, or "custom" with either end, or both. */
export interface QuestionRange {
  preset: RangePreset;
  from?: string | null;
  to?: string | null;
}

/** One filter object for both views; each view's URL is built from it. No range means all time. One scope fills
 *  Explorer's main panel, several its stacked panel; [priorYear] is Explorer's alone. */
export interface QuestionFilters {
  direction?: "income" | "expense" | null;
  scopes: QuestionScope[];
  range?: QuestionRange | null;
  isRefund: boolean;
  priorYear: boolean;
}

/** Why a question asked from Explorer opened the transactions list instead. */
export type RouteReason = "income" | "refunds" | "no_category" | "open_start";

export type UnsupportedKind = "member" | "account" | "merchant" | "text" | "amount" | "other";

export interface UnsupportedFilter {
  kind: UnsupportedKind;
  label: string;
}

/** Filters only, never figures. The server picks [target]; [expressibleIn] lists every view that could show the
 *  filters, for the cross-link. `understood: false` carries nothing to apply. */
export interface QuestionAnswer {
  understood: boolean;
  target?: QuestionTarget | null;
  reason?: RouteReason | null;
  expressibleIn: QuestionTarget[];
  filters?: QuestionFilters | null;
  unsupported: UnsupportedFilter[];
}

export function useAskQuestion(householdId: string) {
  return useMutation({
    mutationFn: async (body: { question: string; language: string; askedFrom: QuestionTarget }) =>
      (await apiClient.post<QuestionAnswer>(`/households/${householdId}/questions`, body)).data,
    // The box shows its own outcome and errors inline.
    meta: { silentSuccess: true, silentError: true },
  });
}

/** The placeholder's example category. Keyed and kept like the catalogue, so whatever refreshes the catalogue
 *  refreshes it too. */
export function useQuestionExampleCategory(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["categories", householdId, "question-example"],
    enabled,
    queryFn: async () =>
      (await apiClient.get<{ categoryCode: string | null }>(`/households/${householdId}/questions/example-category`)).data,
    staleTime: 1000 * 60 * 60,
  });
}
