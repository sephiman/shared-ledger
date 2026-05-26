import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type InterestType = "none" | "simple" | "compound";
export type CompoundingPeriod = "monthly" | "yearly";
export type LoanStatus = "active" | "settled" | "written_off";
export type LoanFrequency = "weekly" | "monthly" | "yearly";
export type LoanStatusFilter = LoanStatus | "all";

export interface LoanSummary {
  id: string;
  borrowerName: string;
  principalAmount: string;
  startDate: string;
  description: string | null;
  interestType: InterestType;
  annualInterestRate: string | null;
  compoundingPeriod: CompoundingPeriod | null;
  status: LoanStatus;
  closedDate: string | null;
  principalRemaining: string;
  accruedInterest: string;
  totalOutstanding: string;
  hasSchedule: boolean;
  scheduleActive: boolean;
}

export interface LoanPayment {
  id: string;
  loanId: string;
  paymentDate: string;
  amount: string;
  description: string | null;
  scheduleId: string | null;
  interestPaid: string;
  principalPaid: string;
}

export interface LoanSchedule {
  id: string;
  loanId: string;
  frequency: LoanFrequency;
  dayOfWeek: number | null;
  dayOfMonth: number | null;
  expectedAmount: string;
  active: boolean;
  lastMaterializedThrough: string | null;
}

export interface LoanDetail {
  summary: LoanSummary;
  payments: LoanPayment[];
  schedule: LoanSchedule | null;
}

export interface LoanListResponse {
  loans: LoanSummary[];
  totalOutstandingActive: string;
  activeCount: number;
  top: LoanSummary[];
}

export interface LoanInput {
  borrowerName: string;
  principalAmount: string;
  startDate: string;
  description?: string | null;
  interestType: InterestType;
  annualInterestRate?: string | null;
  compoundingPeriod?: CompoundingPeriod | null;
}

export interface LoanPaymentInput {
  paymentDate: string;
  amount: string;
  description?: string | null;
}

export interface LoanScheduleInput {
  frequency: LoanFrequency;
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  expectedAmount: string;
  active: boolean;
}

export interface PaymentSplitPreview {
  amount: string;
  interestPaid: string;
  principalPaid: string;
  accruedInterestBefore: string;
  principalBefore: string;
}

const base = (householdId: string) => `/households/${householdId}/loans`;

export function useLoans(householdId: string, status: LoanStatusFilter, top = 0) {
  return useQuery({
    queryKey: ["loans", householdId, status, top],
    queryFn: async () =>
      (
        await apiClient.get<LoanListResponse>(base(householdId), {
          params: { status, ...(top > 0 ? { top } : {}) },
        })
      ).data,
  });
}

export function useLoansSummary(householdId: string) {
  return useQuery({
    queryKey: ["loans-summary", householdId],
    queryFn: async () =>
      (await apiClient.get<LoanListResponse>(base(householdId), { params: { status: "active", top: 3 } })).data,
  });
}

export function useLoan(householdId: string, id: string | null) {
  return useQuery({
    queryKey: ["loan", householdId, id],
    enabled: id != null,
    queryFn: async () => (await apiClient.get<LoanDetail>(`${base(householdId)}/${id}`)).data,
  });
}

function invalidateLoans(qc: ReturnType<typeof useQueryClient>, householdId: string, id?: string) {
  void qc.invalidateQueries({ queryKey: ["loans", householdId] });
  void qc.invalidateQueries({ queryKey: ["loans-summary", householdId] });
  if (id) void qc.invalidateQueries({ queryKey: ["loan", householdId, id] });
}

export function useCreateLoan(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: LoanInput) => (await apiClient.post<LoanDetail>(base(householdId), input)).data,
    onSuccess: () => invalidateLoans(qc, householdId),
  });
}

export function useUpdateLoan(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: LoanInput }) =>
      (await apiClient.patch<LoanDetail>(`${base(householdId)}/${id}`, input)).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.id),
  });
}

export function useDeleteLoan(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`${base(householdId)}/${id}`);
    },
    onSuccess: () => invalidateLoans(qc, householdId),
  });
}

export function useSettleLoan(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, closedDate }: { id: string; closedDate?: string | null }) =>
      (await apiClient.post<LoanDetail>(`${base(householdId)}/${id}/settle`, { closedDate: closedDate ?? null })).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.id),
  });
}

export function useWriteOffLoan(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, closedDate }: { id: string; closedDate?: string | null }) =>
      (await apiClient.post<LoanDetail>(`${base(householdId)}/${id}/write-off`, { closedDate: closedDate ?? null })).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.id),
  });
}

export function useReopenLoan(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await apiClient.post<LoanDetail>(`${base(householdId)}/${id}/reopen`)).data,
    onSuccess: (_d, id) => invalidateLoans(qc, householdId, id),
  });
}

export function useRegisterPayment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ loanId, input }: { loanId: string; input: LoanPaymentInput }) =>
      (await apiClient.post<LoanDetail>(`${base(householdId)}/${loanId}/payments`, input)).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.loanId),
  });
}

export function useUpdatePayment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ loanId, paymentId, input }: { loanId: string; paymentId: string; input: LoanPaymentInput }) =>
      (await apiClient.patch<LoanDetail>(`${base(householdId)}/${loanId}/payments/${paymentId}`, input)).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.loanId),
  });
}

export function useDeletePayment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ loanId, paymentId }: { loanId: string; paymentId: string }) =>
      (await apiClient.delete<LoanDetail>(`${base(householdId)}/${loanId}/payments/${paymentId}`)).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.loanId),
  });
}

export function useUpsertSchedule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ loanId, input }: { loanId: string; input: LoanScheduleInput }) =>
      (await apiClient.put<LoanDetail>(`${base(householdId)}/${loanId}/schedule`, input)).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.loanId),
  });
}

export function useSetScheduleActive(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ loanId, active }: { loanId: string; active: boolean }) =>
      (await apiClient.post<LoanDetail>(`${base(householdId)}/${loanId}/schedule/${active ? "resume" : "pause"}`)).data,
    onSuccess: (_d, v) => invalidateLoans(qc, householdId, v.loanId),
  });
}

export function useDeleteSchedule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (loanId: string) =>
      (await apiClient.delete<LoanDetail>(`${base(householdId)}/${loanId}/schedule`)).data,
    onSuccess: (_d, loanId) => invalidateLoans(qc, householdId, loanId),
  });
}

export function useMaterializeSchedule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (loanId: string) =>
      (await apiClient.post<{ created: number }>(`${base(householdId)}/${loanId}/schedule/run`)).data,
    onSuccess: (_d, loanId) => invalidateLoans(qc, householdId, loanId),
  });
}

export function loansExportUrl(householdId: string): string {
  return `/api${base(householdId)}/export.csv`;
}

export function loanPaymentsExportUrl(householdId: string): string {
  return `/api${base(householdId)}/payments/export.csv`;
}
