import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Liability {
  id: string;
  name: string;
  active: boolean;
  amortizable: boolean;
  chargeDay?: number | null;
  latestBalance?: string | null;
  latestBalanceDate?: string | null;
  // Aggregated schedule figures for amortizable loans (shown in the list row without opening).
  computedBalance?: string | null;
  computedInstalment?: string | null;
}

export interface LiabilityUpsert {
  id?: string;
  name: string;
  active: boolean;
  amortizable: boolean;
  chargeDay?: number | null;
}

export type AssetType = "property" | "vehicle" | "other";

export interface Asset {
  id: string;
  name: string;
  type: AssetType;
  active: boolean;
  latestValue?: string | null;
  latestValueDate?: string | null;
}

export interface ValueEntry {
  id: string;
  date: string;
  value: string;
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
  liabilityName: string;
  balance: string;
}

export interface NamedAssetValue {
  assetId: string;
  name: string;
  value: string;
}

export interface Snapshot {
  id: string;
  snapshotDate: string;
  note: string | null;
  totalAssets: string;
  totalLiabilities: string;
  netWorth: string;
  assets: AssetValue[];
  namedAssets: NamedAssetValue[];
  liabilities: LiabilityBalance[];
  createdAt: string;
}

export interface PrefillView {
  previous: Snapshot | null;
  activeLiabilities: string[];
}

export interface NamedAssetValueInput {
  assetId: string;
  value: string;
}

export interface LiabilityBalanceInput {
  liabilityId: string;
  balance: string;
}

export interface SnapshotInput {
  snapshotDate: string;
  note?: string | null;
  assets: AssetValue[];
  liabilities: LiabilityBalanceInput[];
  namedAssets: NamedAssetValueInput[];
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
  liabilityName: string | null;
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
    mutationFn: async ({ id, name, active, amortizable, chargeDay }: LiabilityUpsert) => {
      const body = { name, active, amortizable, chargeDay: chargeDay ?? null };
      if (id) {
        return (await apiClient.patch<Liability>(`/households/${householdId}/liabilities/${id}`, body)).data;
      }
      return (await apiClient.post<Liability>(`/households/${householdId}/liabilities`, body)).data;
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

export function useLiabilityValues(householdId: string, liabilityId: string, enabled = true) {
  return useQuery({
    queryKey: ["liability-values", householdId, liabilityId],
    enabled,
    queryFn: async () => {
      const raw = (await apiClient.get<{ id: string; balanceDate: string; balance: string }[]>(
        `/households/${householdId}/liabilities/${liabilityId}/values`,
      )).data;
      return raw.map((e): ValueEntry => ({ id: e.id, date: e.balanceDate, value: e.balance }));
    },
  });
}

export function useAddLiabilityValue(householdId: string, liabilityId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ date, value }: { date: string; value: string }) =>
      (await apiClient.post(`/households/${householdId}/liabilities/${liabilityId}/values`, {
        balanceDate: date,
        balance: value,
      })).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["liability-values", householdId, liabilityId] });
      void qc.invalidateQueries({ queryKey: ["liabilities", householdId] });
    },
  });
}

export function useUpdateLiabilityValue(householdId: string, liabilityId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ entryId, date, value }: { entryId: string; date: string; value: string }) =>
      (await apiClient.patch(`/households/${householdId}/liabilities/${liabilityId}/values/${entryId}`, {
        balanceDate: date,
        balance: value,
      })).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["liability-values", householdId, liabilityId] });
      void qc.invalidateQueries({ queryKey: ["liabilities", householdId] });
    },
  });
}

export function useDeleteLiabilityValue(householdId: string, liabilityId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (entryId: string) => {
      await apiClient.delete(`/households/${householdId}/liabilities/${liabilityId}/values/${entryId}`);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["liability-values", householdId, liabilityId] });
      void qc.invalidateQueries({ queryKey: ["liabilities", householdId] });
    },
  });
}

export function useAssets(householdId: string) {
  return useQuery({
    queryKey: ["assets", householdId],
    queryFn: async () => (await apiClient.get<Asset[]>(`/households/${householdId}/assets`)).data,
  });
}

export function useUpsertAsset(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, name, type, active }: { id?: string; name: string; type: AssetType; active: boolean }) => {
      if (id) {
        return (await apiClient.patch<Asset>(`/households/${householdId}/assets/${id}`, { name, type, active })).data;
      }
      return (await apiClient.post<Asset>(`/households/${householdId}/assets`, { name, type, active })).data;
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["assets", householdId] }),
  });
}

export function useDeleteAsset(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/assets/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["assets", householdId] }),
  });
}

export function useAssetValues(householdId: string, assetId: string, enabled = true) {
  return useQuery({
    queryKey: ["asset-values", householdId, assetId],
    enabled,
    queryFn: async () => {
      const raw = (await apiClient.get<{ id: string; valueDate: string; value: string }[]>(
        `/households/${householdId}/assets/${assetId}/values`,
      )).data;
      return raw.map((e): ValueEntry => ({ id: e.id, date: e.valueDate, value: e.value }));
    },
  });
}

export function useAddAssetValue(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ date, value }: { date: string; value: string }) =>
      (await apiClient.post(`/households/${householdId}/assets/${assetId}/values`, {
        valueDate: date,
        value,
      })).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["asset-values", householdId, assetId] });
      void qc.invalidateQueries({ queryKey: ["assets", householdId] });
    },
  });
}

export function useUpdateAssetValue(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ entryId, date, value }: { entryId: string; date: string; value: string }) =>
      (await apiClient.patch(`/households/${householdId}/assets/${assetId}/values/${entryId}`, {
        valueDate: date,
        value,
      })).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["asset-values", householdId, assetId] });
      void qc.invalidateQueries({ queryKey: ["assets", householdId] });
    },
  });
}

export function useDeleteAssetValue(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (entryId: string) => {
      await apiClient.delete(`/households/${householdId}/assets/${assetId}/values/${entryId}`);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["asset-values", householdId, assetId] });
      void qc.invalidateQueries({ queryKey: ["assets", householdId] });
    },
  });
}

export interface NamedValuesAtDate {
  assets: Record<string, string>;
  liabilities: Record<string, string>;
}

/** Computed value of each active named asset/liability at a date (amortizable → schedule, manual → series). */
export function useNamedValuesAt(householdId: string, date: string, enabled: boolean) {
  return useQuery({
    queryKey: ["named-values", householdId, date],
    enabled: enabled && !!date,
    queryFn: async () =>
      (await apiClient.get<NamedValuesAtDate>(`/households/${householdId}/snapshots/named-values`, { params: { date } })).data,
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
