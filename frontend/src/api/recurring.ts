import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface RecurringTemplate {
  id: string;
  direction: "income" | "expense";
  categoryCode: string;
  amount: string;
  description: string | null;
  cadence: "weekly" | "monthly" | "yearly";
  dayOfMonth: number | null;
  dayOfWeek: number | null;
  monthOfYear: number | null;
  dayOfMonthYearly: number | null;
  startDate: string;
  endDate: string | null;
  active: boolean;
  lastMaterializedThrough: string | null;
  nextFireDate: string | null;
}

export interface RecurringInput {
  direction: "income" | "expense";
  categoryCode: string;
  amount: string;
  description?: string | null;
  cadence: "weekly" | "monthly" | "yearly";
  dayOfMonth?: number | null;
  dayOfWeek?: number | null;
  monthOfYear?: number | null;
  dayOfMonthYearly?: number | null;
  startDate: string;
  endDate?: string | null;
  active: boolean;
}

export function useRecurringTemplates(householdId: string) {
  return useQuery({
    queryKey: ["recurring", householdId],
    queryFn: async () => (await apiClient.get<RecurringTemplate[]>(`/households/${householdId}/recurring-templates`)).data,
  });
}

export function useCreateRecurring(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: RecurringInput) => (await apiClient.post(`/households/${householdId}/recurring-templates`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["recurring", householdId] }),
  });
}

export function useUpdateRecurring(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: RecurringInput }) =>
      (await apiClient.patch(`/households/${householdId}/recurring-templates/${id}`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["recurring", householdId] }),
  });
}

export function useDeleteRecurring(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/recurring-templates/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["recurring", householdId] }),
  });
}

export function useMaterializeRecurring(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<{ created: number }>(`/households/${householdId}/recurring-templates/${id}/run`)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["recurring", householdId] });
      void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
    },
  });
}
