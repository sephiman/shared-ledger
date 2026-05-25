import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Category {
  code: string;
  kind: "income" | "expense";
  group: string | null;
  sortOrder: number;
  essential: boolean;
  name: string;
  custom: boolean;
}

export interface AssetClass {
  code: string;
  sortOrder: number;
}

export interface CustomCategoryCreateInput {
  name: string;
  kind: "income" | "expense";
  groupCode?: string | null;
  essential: boolean;
}

export interface CustomCategoryUpdateInput {
  name?: string;
  groupCode?: string | null;
  essential?: boolean;
}

export function useCategories(householdId: string | null | undefined) {
  return useQuery({
    queryKey: ["categories", householdId],
    enabled: !!householdId,
    queryFn: async () =>
      (await apiClient.get<Category[]>(`/households/${householdId}/categories`)).data,
    staleTime: 1000 * 60 * 60,
  });
}

export function useAssetClasses() {
  return useQuery({
    queryKey: ["asset-classes"],
    queryFn: async () => (await apiClient.get<AssetClass[]>("/asset-classes")).data,
    staleTime: 1000 * 60 * 60,
  });
}

function invalidateCategoryDependents(qc: QueryClient, householdId: string) {
  void qc.invalidateQueries({ queryKey: ["categories", householdId] });
  void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
  void qc.invalidateQueries({ queryKey: ["quick-chips", householdId] });
  void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
  void qc.invalidateQueries({ queryKey: ["budgets-month-summary", householdId] });
  void qc.invalidateQueries({ queryKey: ["recurring", householdId] });
  void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
}

export function useCreateCustomCategory(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CustomCategoryCreateInput) =>
      (await apiClient.post<Category>(`/households/${householdId}/categories`, input)).data,
    onSuccess: () => invalidateCategoryDependents(qc, householdId),
  });
}

export function useUpdateCustomCategory(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { code: string; patch: CustomCategoryUpdateInput }) =>
      (await apiClient.patch<Category>(
        `/households/${householdId}/categories/${encodeURIComponent(input.code)}`,
        input.patch,
      )).data,
    onSuccess: () => invalidateCategoryDependents(qc, householdId),
  });
}

export function useDeleteCustomCategory(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (code: string) => {
      await apiClient.delete(`/households/${householdId}/categories/${encodeURIComponent(code)}`);
    },
    onSuccess: () => invalidateCategoryDependents(qc, householdId),
  });
}
