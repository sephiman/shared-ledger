import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type InterestType = "none" | "simple" | "compound";
export type CompoundingPeriod = "monthly" | "yearly";
export type LendingStatus = "active" | "settled" | "written_off";
export type LendingFrequency = "weekly" | "monthly" | "yearly";
export type LendingStatusFilter = LendingStatus | "all";

export interface LendingSummary {
  id: string;
  borrowerName: string;
  principalAmount: string;
  startDate: string;
  description: string | null;
  interestType: InterestType;
  annualInterestRate: string | null;
  compoundingPeriod: CompoundingPeriod | null;
  status: LendingStatus;
  closedDate: string | null;
  principalRemaining: string;
  accruedInterest: string;
  totalOutstanding: string;
  hasSchedule: boolean;
  scheduleActive: boolean;
}

export interface LendingPayment {
  id: string;
  lendingId: string;
  paymentDate: string;
  amount: string;
  description: string | null;
  scheduleId: string | null;
  interestPaid: string;
  principalPaid: string;
}

export interface LendingSchedule {
  id: string;
  lendingId: string;
  frequency: LendingFrequency;
  dayOfWeek: number | null;
  dayOfMonth: number | null;
  expectedAmount: string;
  active: boolean;
  lastMaterializedThrough: string | null;
}

export interface LendingDetail {
  summary: LendingSummary;
  payments: LendingPayment[];
  schedule: LendingSchedule | null;
}

export interface LendingListResponse {
  lendings: LendingSummary[];
  totalOutstandingActive: string;
  activeCount: number;
  top: LendingSummary[];
}

export interface LendingInput {
  borrowerName: string;
  principalAmount: string;
  startDate: string;
  description?: string | null;
  interestType: InterestType;
  annualInterestRate?: string | null;
  compoundingPeriod?: CompoundingPeriod | null;
}

export interface LendingPaymentInput {
  paymentDate: string;
  amount: string;
  description?: string | null;
}

export interface LendingScheduleInput {
  frequency: LendingFrequency;
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  expectedAmount: string;
  active: boolean;
}

const base = (householdId: string) => `/households/${householdId}/lendings`;

export function useLendings(householdId: string, status: LendingStatusFilter, top = 0) {
  return useQuery({
    queryKey: ["lendings", householdId, status, top],
    queryFn: async () =>
      (
        await apiClient.get<LendingListResponse>(base(householdId), {
          params: { status, ...(top > 0 ? { top } : {}) },
        })
      ).data,
  });
}

export function useLendingsSummary(householdId: string) {
  return useQuery({
    queryKey: ["lendings-summary", householdId],
    queryFn: async () =>
      (await apiClient.get<LendingListResponse>(base(householdId), { params: { status: "active", top: 3 } })).data,
  });
}

export function useLending(householdId: string, id: string | null) {
  return useQuery({
    queryKey: ["lending", householdId, id],
    enabled: id != null,
    queryFn: async () => (await apiClient.get<LendingDetail>(`${base(householdId)}/${id}`)).data,
  });
}

function invalidateLendings(qc: ReturnType<typeof useQueryClient>, householdId: string, id?: string) {
  void qc.invalidateQueries({ queryKey: ["lendings", householdId] });
  void qc.invalidateQueries({ queryKey: ["lendings-summary", householdId] });
  if (id) void qc.invalidateQueries({ queryKey: ["lending", householdId, id] });
}

export function useCreateLending(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: LendingInput) => (await apiClient.post<LendingDetail>(base(householdId), input)).data,
    onSuccess: () => invalidateLendings(qc, householdId),
  });
}

export function useUpdateLending(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: LendingInput }) =>
      (await apiClient.patch<LendingDetail>(`${base(householdId)}/${id}`, input)).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.id),
  });
}

export function useSettleLending(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, closedDate }: { id: string; closedDate?: string | null }) =>
      (await apiClient.post<LendingDetail>(`${base(householdId)}/${id}/settle`, { closedDate: closedDate ?? null })).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.id),
  });
}

export function useWriteOffLending(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, closedDate }: { id: string; closedDate?: string | null }) =>
      (await apiClient.post<LendingDetail>(`${base(householdId)}/${id}/write-off`, { closedDate: closedDate ?? null })).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.id),
  });
}

export function useReopenLending(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await apiClient.post<LendingDetail>(`${base(householdId)}/${id}/reopen`)).data,
    onSuccess: (_d, id) => invalidateLendings(qc, householdId, id),
  });
}

export function useRegisterPayment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ lendingId, input }: { lendingId: string; input: LendingPaymentInput }) =>
      (await apiClient.post<LendingDetail>(`${base(householdId)}/${lendingId}/payments`, input)).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.lendingId),
  });
}

export function useUpdatePayment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ lendingId, paymentId, input }: { lendingId: string; paymentId: string; input: LendingPaymentInput }) =>
      (await apiClient.patch<LendingDetail>(`${base(householdId)}/${lendingId}/payments/${paymentId}`, input)).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.lendingId),
  });
}

export function useDeletePayment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ lendingId, paymentId }: { lendingId: string; paymentId: string }) =>
      (await apiClient.delete<LendingDetail>(`${base(householdId)}/${lendingId}/payments/${paymentId}`)).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.lendingId),
  });
}

export function useUpsertSchedule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ lendingId, input }: { lendingId: string; input: LendingScheduleInput }) =>
      (await apiClient.put<LendingDetail>(`${base(householdId)}/${lendingId}/schedule`, input)).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.lendingId),
  });
}

export function useSetScheduleActive(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ lendingId, active }: { lendingId: string; active: boolean }) =>
      (await apiClient.post<LendingDetail>(`${base(householdId)}/${lendingId}/schedule/${active ? "resume" : "pause"}`)).data,
    onSuccess: (_d, v) => invalidateLendings(qc, householdId, v.lendingId),
  });
}

export function useDeleteSchedule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (lendingId: string) =>
      (await apiClient.delete<LendingDetail>(`${base(householdId)}/${lendingId}/schedule`)).data,
    onSuccess: (_d, lendingId) => invalidateLendings(qc, householdId, lendingId),
  });
}

export function useMaterializeSchedule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (lendingId: string) =>
      (await apiClient.post<{ created: number }>(`${base(householdId)}/${lendingId}/schedule/run`)).data,
    onSuccess: (_d, lendingId) => invalidateLendings(qc, householdId, lendingId),
  });
}

export function lendingsExportUrl(householdId: string): string {
  return `/api${base(householdId)}/export.csv`;
}

export function lendingPaymentsExportUrl(householdId: string): string {
  return `/api${base(householdId)}/payments/export.csv`;
}
