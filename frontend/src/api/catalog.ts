import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Category {
  code: string;
  kind: "income" | "expense";
  group: string | null;
  sortOrder: number;
  essential: boolean;
}

export interface AssetClass {
  code: string;
  sortOrder: number;
}

export function useCategories() {
  return useQuery({
    queryKey: ["categories"],
    queryFn: async () => (await apiClient.get<Category[]>("/categories")).data,
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
