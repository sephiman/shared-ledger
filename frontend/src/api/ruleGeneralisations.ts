import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import type { AiSuggestionRunStatus } from "./aiSuggestions";
import type { CategorizationRule, Direction, RuleField } from "./banks";

export interface RuleGeneralisationRun {
  id: string;
  status: AiSuggestionRunStatus;
  rulesTotal: number;
  rulesSent: number;
  proposalsReturned: number;
  proposalsDiscarded: number;
  proposalsShown: number;
  proposalsAccepted: number;
  startedAt: string;
  finishedAt: string | null;
}

export type ProposalProblem =
  | "single_rule"
  | "unknown_rule"
  | "other_field"
  | "mixed_rules"
  | "unknown_category"
  | "too_short"
  | "denied"
  | "not_covering";

/** A movement the household already has whose category accepting would change. */
export interface ReplayChange {
  counterparty: string | null;
  description: string | null;
  fromCategory: string;
  toCategory: string | null;
}

/** The text fields a generalised rule can match on. */
export type GeneralisableField = Extract<RuleField, "counterparty" | "description">;

/** The server's verdict; the app never re-implements it. */
export interface ProposalCheck {
  status: "ok" | "conflict" | "invalid";
  problem: ProposalProblem | null;
  collisionCount: number;
  collisionExamples: string[];
  changeCount: number;
  changeExamples: ReplayChange[];
}

export interface RuleGeneralisationProposal {
  id: string;
  pattern: string;
  field: GeneralisableField;
  direction: Direction;
  categoryCode: string;
  confidence: number;
  absorbed: { id: string; matchValue: string }[];
  check: ProposalCheck;
}

export interface RuleGeneralisationStatus {
  /** Counterparty and description rules: the kinds a proposal can replace. */
  eligibleRules: number;
  minRules: number;
  maxRules: number;
  run: RuleGeneralisationRun | null;
  proposals: RuleGeneralisationProposal[] | null;
  proposalsExpired: boolean;
}

export interface GeneralisationRequest {
  pattern: string;
  field: GeneralisableField;
  direction: Direction;
  categoryCode: string;
  absorbedRuleIds: string[];
  runId?: string;
}

const path = (householdId: string) => `/households/${householdId}/banks/rules/generalisations`;
const POLL_MS = 1000;

/** Under the "banks" key so rule edits re-validate the proposals with it. Polls while a run is in progress. */
export function useRuleGeneralisationStatus(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["banks", householdId, "rule-generalisations"],
    queryFn: async () => (await apiClient.get<RuleGeneralisationStatus>(path(householdId))).data,
    enabled,
    refetchInterval: (query) => (query.state.data?.run?.status === "running" ? POLL_MS : false),
  });
}

export function useStartRuleGeneralisation(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => (await apiClient.post<RuleGeneralisationRun>(path(householdId))).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId, "rule-generalisations"] }),
    meta: { silentSuccess: true },
  });
}

export function useValidateGeneralisation(householdId: string) {
  return useMutation({
    mutationFn: async (request: GeneralisationRequest) =>
      (await apiClient.post<ProposalCheck>(`${path(householdId)}/validate`, request)).data,
    meta: { silentSuccess: true },
  });
}

export function useAcceptGeneralisation(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (request: GeneralisationRequest) =>
      (await apiClient.post<{ rule: CategorizationRule; deleted: number }>(`${path(householdId)}/accept`, request)).data,
    onSettled: () => void qc.invalidateQueries({ queryKey: ["banks", householdId] }),
    meta: { silentError: true },
  });
}
