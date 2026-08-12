import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

/** Enough of the refunded purchase to name and link it from the refund's row. */
export interface RefundOfSummary {
  id: string;
  occurrenceDate: string;
  categoryCode: string;
  amount: string;
  description: string | null;
}

export interface Transaction {
  id: string;
  occurrenceDate: string;
  direction: "income" | "expense";
  categoryCode: string;
  /** Negative on a refund: money coming back is a negative expense. */
  amount: string;
  description: string | null;
  recurringTemplateId: string | null;
  createdByUserId: string;
  updatedByUserId: string;
  createdAt: string;
  updatedAt: string;
  isRefund: boolean;
  refundOfTransactionId: string | null;
  /** Null when the refund has no link, or its original was deleted. */
  refundOf: RefundOfSummary | null;
  /** On a refunded purchase: the (negative) total that has come back, and how many refunds did it. */
  refundedTotal: string | null;
  refundCount: number | null;
}

export interface TransactionPage {
  items: Transaction[];
  page: number;
  size: number;
  total: number;
}

export interface TransactionFilters {
  from?: string;
  to?: string;
  direction?: "income" | "expense";
  categoryCode?: string;
  categoryGroup?: string;
  /** Undefined means both; refunds are expenses, so the direction filter already includes them. */
  isRefund?: boolean;
  /** Description contains — used by the original-expense picker. */
  q?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export function useTransactions(householdId: string, filters: TransactionFilters) {
  return useQuery({
    queryKey: ["transactions", householdId, filters],
    queryFn: async () => {
      const res = await apiClient.get<TransactionPage>(`/households/${householdId}/transactions`, {
        params: filters,
      });
      return res.data;
    },
  });
}

/** One transaction by id; used to open the refund picker on an already-linked purchase. */
export function useTransaction(householdId: string, id: string | null) {
  return useQuery({
    queryKey: ["transactions", householdId, "one", id],
    enabled: !!id,
    queryFn: async () =>
      (await apiClient.get<Transaction>(`/households/${householdId}/transactions/${id}`)).data,
  });
}

export interface TransactionInput {
  occurrenceDate: string;
  direction: "income" | "expense";
  categoryCode: string;
  /** Negative when `isRefund` is set, positive otherwise; the server enforces both. */
  amount: string;
  description?: string | null;
  isRefund?: boolean;
  refundOfTransactionId?: string | null;
}

export function useCreateTransaction(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: TransactionInput) => {
      const res = await apiClient.post<Transaction>(`/households/${householdId}/transactions`, input);
      return res.data;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
      void qc.invalidateQueries({ queryKey: ["quick-chips", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
      void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
    },
  });
}

export function useUpdateTransaction(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TransactionInput }) => {
      const res = await apiClient.patch<Transaction>(`/households/${householdId}/transactions/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
      void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
    },
  });
}

export function useDeleteTransaction(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/transactions/${id}`);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
    },
  });
}

export interface QuickChip {
  categoryCode: string;
  count: number;
}

export function useQuickChips(householdId: string) {
  return useQuery({
    queryKey: ["quick-chips", householdId],
    queryFn: async () => (await apiClient.get<QuickChip[]>(`/households/${householdId}/transactions/quick-chips`)).data,
  });
}
