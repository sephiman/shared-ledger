import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type AmortizationMethod = "french" | "german" | "interest_only" | "zero";
export type PrepaymentMode = "reduce_term" | "reduce_instalment";
export type StartMode = "current_balance" | "origin";

export interface Part {
  id: string;
  label: string | null;
  method: AmortizationMethod;
  originalPrincipal: string;
  annualRate: string;
  termMonths: number | null;
  instalment: string | null;
  startDate: string;
}

export interface PartInput {
  label?: string | null;
  method: AmortizationMethod;
  startMode: StartMode;
  originalPrincipal: string;
  annualRate: string;
  termMonths?: number | null;
  endDate?: string | null;
  instalment?: string | null;
  startDate: string;
}

export interface Revision {
  id: string;
  effectiveDate: string;
  annualRate: string;
}

export interface Prepayment {
  id: string;
  partId: string;
  prepaymentDate: string;
  amount: string;
  mode: PrepaymentMode;
}

export interface ScheduleRow {
  date: string;
  interest: string;
  principal: string;
  balance: string;
  instalment: string;
}

export interface ChargedEntry {
  date: string;
  interest: string;
  principal: string;
  resultingBalance: string;
}

export interface PartSchedule {
  partId: string;
  label: string | null;
  method: AmortizationMethod;
  startMode: StartMode;
  originalPrincipal: string;
  annualRate: string;
  startDate: string;
  termMonths: number | null;
  instalmentInput: string | null;
  anchorDate: string | null;
  anchorBalance: string | null;
  currentBalance: string;
  instalment: string;
  payoffDate: string | null;
  totalInterestRemaining: string;
  rows: ScheduleRow[];
  charged: ChargedEntry[];
  revisions: Revision[];
  prepayments: Prepayment[];
}

export interface LiabilitySchedule {
  liabilityId: string;
  chargeDay: number | null;
  currentBalance: string;
  monthlyInstalment: string;
  totalPrincipal: string;
  interestPaid: string;
  principalPaid: string;
  interestRemaining: string;
  totalInterest: string;
  progress: string; // fraction 0..1
  parts: PartSchedule[];
}

export interface SimulationResult {
  interestSaved: string;
  baselinePayoffDate: string | null;
  newPayoffDate: string | null;
  baselineInstalment: string;
  newInstalment: string;
  baselineTotalInterest: string;
  newTotalInterest: string;
}

function base(householdId: string, liabilityId: string) {
  return `/households/${householdId}/liabilities/${liabilityId}/amortization`;
}

export function useAmortizationSchedule(householdId: string, liabilityId: string, enabled = true) {
  return useQuery({
    queryKey: ["amortization-schedule", householdId, liabilityId],
    enabled,
    queryFn: async () => (await apiClient.get<LiabilitySchedule>(`${base(householdId, liabilityId)}/schedule`)).data,
  });
}

function useInvalidateSchedule(householdId: string, liabilityId: string) {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ["amortization-schedule", householdId, liabilityId] });
    void qc.invalidateQueries({ queryKey: ["liabilities", householdId] });
  };
}

export function useCreatePart(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async (input: PartInput) =>
      (await apiClient.post<Part>(`${base(householdId, liabilityId)}/parts`, input)).data,
    onSuccess: invalidate,
  });
}

export function useUpdatePart(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: PartInput }) =>
      (await apiClient.patch<Part>(`${base(householdId, liabilityId)}/parts/${id}`, input)).data,
    onSuccess: invalidate,
  });
}

export function useDeletePart(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async (partId: string) => {
      await apiClient.delete(`${base(householdId, liabilityId)}/parts/${partId}`);
    },
    onSuccess: invalidate,
  });
}

export function useAddRevision(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async ({ partId, effectiveDate, annualRate }: { partId: string; effectiveDate: string; annualRate: string }) =>
      (await apiClient.post<Revision>(`${base(householdId, liabilityId)}/parts/${partId}/revisions`, { effectiveDate, annualRate })).data,
    onSuccess: invalidate,
  });
}

export function useDeleteRevision(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async ({ partId, revisionId }: { partId: string; revisionId: string }) => {
      await apiClient.delete(`${base(householdId, liabilityId)}/parts/${partId}/revisions/${revisionId}`);
    },
    onSuccess: invalidate,
  });
}

export function useReAnchor(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async ({ partId, anchorDate, anchorBalance }: { partId: string; anchorDate: string; anchorBalance: string }) =>
      (await apiClient.post(`${base(householdId, liabilityId)}/parts/${partId}/anchor`, { anchorDate, anchorBalance })).data,
    onSuccess: invalidate,
  });
}

export function useRecordPrepayment(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async ({ partId, prepaymentDate, amount, mode }: { partId: string; prepaymentDate: string; amount: string; mode: PrepaymentMode }) =>
      (await apiClient.post<Prepayment>(`${base(householdId, liabilityId)}/parts/${partId}/prepayments`, { prepaymentDate, amount, mode })).data,
    onSuccess: invalidate,
  });
}

export function useDeletePrepayment(householdId: string, liabilityId: string) {
  const invalidate = useInvalidateSchedule(householdId, liabilityId);
  return useMutation({
    mutationFn: async ({ partId, prepaymentId }: { partId: string; prepaymentId: string }) => {
      await apiClient.delete(`${base(householdId, liabilityId)}/parts/${partId}/prepayments/${prepaymentId}`);
    },
    onSuccess: invalidate,
  });
}

export function useSimulatePrepayment(householdId: string, liabilityId: string) {
  return useMutation({
    mutationFn: async ({ partId, prepaymentDate, amount, mode }: { partId: string; prepaymentDate: string; amount: string; mode: PrepaymentMode }) =>
      (await apiClient.post<SimulationResult>(`${base(householdId, liabilityId)}/parts/${partId}/simulate`, { prepaymentDate, amount, mode })).data,
  });
}
