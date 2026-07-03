import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Liability {
  id: string;
  name: string;
  active: boolean;
}

export type ValueSource = "computed" | "overridden" | "carried_over";

export type SnapshotFrequency = "daily" | "weekly" | "monthly";

export interface AutoSnapshotSettings {
  enabled: boolean;
  frequency: SnapshotFrequency;
}

export interface AssetValue {
  assetClassCode: string;
  value: string;
  // Present on responses; optional on requests (null lets the server infer).
  valueSource?: ValueSource | null;
}

export interface LiabilityBalance {
  liabilityId: string;
  balance: string;
}

export interface Snapshot {
  id: string;
  snapshotDate: string;
  note: string | null;
  totalAssets: string;
  totalLiabilities: string;
  netWorth: string;
  assets: AssetValue[];
  liabilities: LiabilityBalance[];
  createdAt: string;
}

export interface PrefillView {
  previous: Snapshot | null;
  activeLiabilities: string[];
}

export interface SnapshotInput {
  snapshotDate: string;
  note?: string | null;
  assets: AssetValue[];
  liabilities: LiabilityBalance[];
  confirmLargeChanges: boolean;
}

export interface EvolutionPoint {
  snapshotDate: string;
  byClass: Record<string, string>;
  totalAssets: string;
  totalLiabilities: string;
  netWorth: string;
}

export interface Movement {
  id: string;
  movementDate: string;
  type: "contribution" | "withdrawal" | "debt_payment";
  assetClassCode: string | null;
  liabilityId: string | null;
  amount: string;
  description: string | null;
}

export interface MovementInput {
  movementDate: string;
  type: "contribution" | "withdrawal" | "debt_payment";
  assetClassCode?: string | null;
  liabilityId?: string | null;
  amount: string;
  description?: string | null;
}

export interface CumulativeBucket {
  key: string;
  contributions: string;
  withdrawals: string;
  debtPayments: string;
}

export function useLiabilities(householdId: string) {
  return useQuery({
    queryKey: ["liabilities", householdId],
    queryFn: async () => (await apiClient.get<Liability[]>(`/households/${householdId}/liabilities`)).data,
  });
}

export function useUpsertLiability(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, name, active }: { id?: string; name: string; active: boolean }) => {
      if (id) {
        return (await apiClient.patch<Liability>(`/households/${householdId}/liabilities/${id}`, { name, active })).data;
      }
      return (await apiClient.post<Liability>(`/households/${householdId}/liabilities`, { name, active })).data;
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["liabilities", householdId] }),
  });
}

export function useDeleteLiability(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/liabilities/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["liabilities", householdId] }),
  });
}

export function useSnapshots(householdId: string) {
  return useQuery({
    queryKey: ["snapshots", householdId],
    queryFn: async () => (await apiClient.get<Snapshot[]>(`/households/${householdId}/snapshots`)).data,
  });
}

export function useSnapshotPrefill(householdId: string) {
  return useQuery({
    queryKey: ["snapshot-prefill", householdId],
    queryFn: async () => (await apiClient.get<PrefillView>(`/households/${householdId}/snapshots/previous-for-prefill`)).data,
  });
}

export function useCreateSnapshot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: SnapshotInput) =>
      (await apiClient.post<Snapshot>(`/households/${householdId}/snapshots`, input)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["snapshots", householdId] });
      void qc.invalidateQueries({ queryKey: ["snapshot-prefill", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
    },
  });
}

export function useUpdateSnapshot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: SnapshotInput }) =>
      (await apiClient.patch<Snapshot>(`/households/${householdId}/snapshots/${id}`, input)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["snapshots", householdId] });
      void qc.invalidateQueries({ queryKey: ["snapshot-prefill", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
    },
  });
}

export function useDeleteSnapshot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/snapshots/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["snapshots", householdId] }),
  });
}

export function useAutoSnapshotSettings(householdId: string) {
  return useQuery({
    queryKey: ["auto-snapshot-settings", householdId],
    queryFn: async () =>
      (await apiClient.get<AutoSnapshotSettings>(`/households/${householdId}/snapshots/auto-settings`)).data,
  });
}

export function useUpdateAutoSnapshotSettings(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: AutoSnapshotSettings) =>
      (await apiClient.put<AutoSnapshotSettings>(`/households/${householdId}/snapshots/auto-settings`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["auto-snapshot-settings", householdId] }),
  });
}

export function useMovements(householdId: string, params: Record<string, unknown> = {}) {
  return useQuery({
    queryKey: ["movements", householdId, params],
    queryFn: async () =>
      (await apiClient.get<{ items: Movement[]; total: number; page: number; size: number }>(
        `/households/${householdId}/movements`,
        { params },
      )).data,
  });
}

export function useCreateMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: MovementInput) =>
      (await apiClient.post<Movement>(`/households/${householdId}/movements`, input)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["movements", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
    },
  });
}

export function useUpdateMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: MovementInput }) =>
      (await apiClient.patch<Movement>(`/households/${householdId}/movements/${id}`, input)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["movements", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
    },
  });
}

export function useDeleteMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/movements/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["movements", householdId] }),
  });
}
