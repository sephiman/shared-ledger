import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { CashFlowEventKind, FirePreviewRequest } from "./fire";

export type FireAiRunStatus = "running" | "completed" | "failed" | "interrupted";

export interface FireAiCitation {
  path: string;
  value: unknown;
  grounded: boolean;
  context?: string | null;
}

export interface FireAiScope {
  referenceTier: string;
  referenceScenarioIndex: number;
  referenceScenarioName: string;
  snapshotDate: string | null;
  coveragePercent: number | null;
  estimatedYear: number | null;
  probability: number | null;
}

export interface FireAiStrength {
  title: string;
  detail: string;
  citations: FireAiCitation[];
}

export interface FireAiImprovement {
  title: string;
  detail: string;
  severity: "critical" | "warning" | "opportunity" | string | null;
  citations: FireAiCitation[];
}

export interface FireAiSuggestedWhatIf {
  title: string;
  rationale: string;
  expectedImpact: string | null;
  preview: FirePreviewRequest | null;
  citations: FireAiCitation[];
}

export interface FireAiObservation {
  claim: string;
  confidence: "high" | "medium" | "low" | string | null;
  citations: FireAiCitation[];
}

export interface FireAiDataGap {
  topic: string;
  reason: string;
  citations: FireAiCitation[];
}

export interface FireAiFollowup {
  id: string;
  question: string;
  answer: string;
  citations: FireAiCitation[];
  citationsTotal: number;
  citationsUngrounded: number;
  inputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number | null;
  createdAt: string;
}

export interface FireAiRun {
  id: string;
  status: FireAiRunStatus;
  createdAt: string;
  finishedAt: string | null;
  scope: FireAiScope;
  model: string;
  promptVersion: string;
  summary: string | null;
  evolution: string | null;
  strengths: FireAiStrength[];
  improvements: FireAiImprovement[];
  suggestedWhatIfs: FireAiSuggestedWhatIf[];
  observations: FireAiObservation[];
  dataGaps: FireAiDataGap[];
  observationsCount: number | null;
  dataGapsCount: number | null;
  citationsTotal: number | null;
  citationsUngrounded: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  reasoningTokens: number | null;
  latencyMs: number | null;
  errorCode: string | null;
  followups: FireAiFollowup[];
}

export const FIRE_AI_FOLLOWUP_MAX_CHARS = 500;
const POLL_MS = 1500;
const runsKey = (householdId: string) => ["fire-ai-analysis", householdId];

export function useFireAiRuns(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: runsKey(householdId),
    queryFn: async () => (await apiClient.get<FireAiRun[]>(`/households/${householdId}/fire/ai-analysis`)).data,
    enabled,
    refetchInterval: (query) => (query.state.data?.some((r) => r.status === "running") ? POLL_MS : false),
  });
}

export function useStartFireAiAnalysis(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => (await apiClient.post<FireAiRun>(`/households/${householdId}/fire/ai-analysis`)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: runsKey(householdId) }),
    meta: { silentSuccess: true, silentError: true },
  });
}

export function useAskFireAiFollowup(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ runId, question }: { runId: string; question: string }) =>
      (await apiClient.post<FireAiFollowup>(`/households/${householdId}/fire/ai-analysis/${runId}/followups`, { question })).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: runsKey(householdId) }),
    meta: { silentSuccess: true, silentError: true },
  });
}

export function useDeleteFireAiRun(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (runId: string) => {
      await apiClient.delete(`/households/${householdId}/fire/ai-analysis/${runId}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: runsKey(householdId) }),
    meta: { silentSuccess: true },
  });
}

// --- Natural-language FIRE What-If translator --------------------------------

export interface FireDecimalChange {
  kind: "set" | "delta" | "percent";
  value: string;
}

export interface FireYearChange {
  kind: "set" | "delta";
  value: number;
}

export interface FireQuestionEvent {
  year: number;
  kind: CashFlowEventKind;
  amount: string;
  label: string;
  template: string | null;
}

export interface FireQuestionWhatIf {
  monthlyContribution: FireDecimalChange | null;
  monthlySpending: FireDecimalChange | null;
  targetYear: FireYearChange | null;
  safeWithdrawalRatePct: FireDecimalChange | null;
  lumpSum: string | null;
  returnShiftPct: string | null;
  cashFlowEvents: FireQuestionEvent[];
}

export interface FireQuestionUnsupported {
  kind: "tax" | "inflation" | "asset_class" | "tier" | "other";
  label: string;
}

export interface FireQuestionAnswer {
  understood: boolean;
  whatIf: FireQuestionWhatIf | null;
  unsupported: FireQuestionUnsupported[];
}

export function useAskFireQuestion(householdId: string) {
  return useMutation({
    mutationFn: async (body: { question: string; language: string }) =>
      (await apiClient.post<FireQuestionAnswer>(`/households/${householdId}/fire/question`, body)).data,
    meta: { silentSuccess: true, silentError: true },
  });
}
