import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface RowError {
  row: number;
  code: string;
  field?: string | null;
  value?: string | null;
}

export interface SkippedRow {
  row: number;
  summary: string;
}

export interface AdjustedRow {
  row: number;
  originalSummary: string;
  newDescription: string;
}

export interface PreviewSummary {
  totalRows: number;
  wouldInsert: number;
  wouldSkip: number;
  wouldReplace: number;
  errorCount: number;
  errors: RowError[];
  truncatedErrors: boolean;
  skippedRows: SkippedRow[];
  truncatedSkipped: boolean;
  adjustedDescriptions: AdjustedRow[];
  adjustedCount: number;
  truncatedAdjusted: boolean;
  sumIncome?: string | null;
  sumExpense?: string | null;
  sumAssets?: string | null;
  sumLiabilities?: string | null;
  sumContributions?: string | null;
  sumWithdrawals?: string | null;
  sumDebtPayments?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
}

export interface ExecuteResult {
  inserted: number;
  skipped: number;
  replaced: number;
  failedRowsCsv?: string | null;
  skippedRows: SkippedRow[];
  truncatedSkipped: boolean;
  adjustedDescriptions: AdjustedRow[];
  adjustedCount: number;
  truncatedAdjusted: boolean;
}

export type SnapshotPolicy = "skip" | "replace" | "abort";

function asForm(file: File, extra: Record<string, string> = {}): FormData {
  const form = new FormData();
  form.append("file", file);
  for (const [k, v] of Object.entries(extra)) form.append(k, v);
  return form;
}

export function usePreviewTransactions(householdId: string) {
  return useMutation({
    mutationFn: async (file: File) => {
      const res = await apiClient.post<PreviewSummary>(
        `/households/${householdId}/transactions/import/preview`,
        asForm(file),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    meta: { silentSuccess: true },
  });
}

export function useExecuteTransactions(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) => {
      const res = await apiClient.post<ExecuteResult>(
        `/households/${householdId}/transactions/import/execute`,
        asForm(file),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
      void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
    },
  });
}

export function usePreviewMovements(householdId: string) {
  return useMutation({
    mutationFn: async (file: File) => {
      const res = await apiClient.post<PreviewSummary>(
        `/households/${householdId}/movements/import/preview`,
        asForm(file),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    meta: { silentSuccess: true },
  });
}

export function useExecuteMovements(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) => {
      const res = await apiClient.post<ExecuteResult>(
        `/households/${householdId}/movements/import/execute`,
        asForm(file),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["movements", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
    },
  });
}

export function usePreviewRecurring(householdId: string) {
  return useMutation({
    mutationFn: async (file: File) => {
      const res = await apiClient.post<PreviewSummary>(
        `/households/${householdId}/recurring-templates/import/preview`,
        asForm(file),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    meta: { silentSuccess: true },
  });
}

export function useExecuteRecurring(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) => {
      const res = await apiClient.post<ExecuteResult>(
        `/households/${householdId}/recurring-templates/import/execute`,
        asForm(file),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["recurring", householdId] });
    },
  });
}

export function usePreviewSnapshots(householdId: string) {
  return useMutation({
    mutationFn: async ({ file, policy }: { file: File; policy: SnapshotPolicy }) => {
      const res = await apiClient.post<PreviewSummary>(
        `/households/${householdId}/snapshots/import/preview`,
        asForm(file, { duplicatePolicy: policy }),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    meta: { silentSuccess: true },
  });
}

export function useExecuteSnapshots(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ file, policy }: { file: File; policy: SnapshotPolicy }) => {
      const res = await apiClient.post<ExecuteResult>(
        `/households/${householdId}/snapshots/import/execute`,
        asForm(file, { duplicatePolicy: policy }),
        { headers: { "Content-Type": null } },
      );
      return res.data;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["snapshots", householdId] });
      void qc.invalidateQueries({ queryKey: ["snapshot-prefill", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
    },
  });
}
