import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Budget {
  id: string;
  year: number;
  month: number | null;
  categoryCode: string;
  amount: string;
  updatedByUserId: string;
}

export interface BudgetUpsertItem {
  year: number;
  month?: number | null;
  categoryCode: string;
  amount: string;
}

export interface MonthSummaryRow {
  categoryCode: string;
  budget: string;
  spent: string;
  pace: string;
  projection: string;
  percent: number;
}

export interface MonthSummary {
  year: number;
  month: number;
  daysElapsed: number;
  daysInMonth: number;
  rows: MonthSummaryRow[];
}

export function useBudgets(householdId: string, year: number, month?: number) {
  return useQuery({
    queryKey: ["budgets", householdId, year, month ?? null],
    queryFn: async () => {
      const params: Record<string, number> = { year };
      if (month) params.month = month;
      return (await apiClient.get<Budget[]>(`/households/${householdId}/budgets`, { params })).data;
    },
  });
}

export function useMonthSummary(householdId: string, year: number, month: number) {
  return useQuery({
    queryKey: ["budgets-month-summary", householdId, year, month],
    queryFn: async () =>
      (await apiClient.get<MonthSummary>(`/households/${householdId}/budgets/month-summary`, { params: { year, month } })).data,
  });
}

export function useUpsertBudgets(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (items: BudgetUpsertItem[]) =>
      (await apiClient.put<Budget[]>(`/households/${householdId}/budgets`, { items })).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
      void qc.invalidateQueries({ queryKey: ["budgets-month-summary", householdId] });
    },
  });
}
