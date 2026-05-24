import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Transaction {
  id: string;
  occurrenceDate: string;
  direction: "income" | "expense";
  categoryCode: string;
  amount: string;
  description: string | null;
  recurringTemplateId: string | null;
  createdByUserId: string;
  updatedByUserId: string;
  createdAt: string;
  updatedAt: string;
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

export interface TransactionInput {
  occurrenceDate: string;
  direction: "income" | "expense";
  categoryCode: string;
  amount: string;
  description?: string | null;
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
