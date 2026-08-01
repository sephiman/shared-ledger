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
    onSuccess: () => invalidateLiabilityValues(qc, householdId, liabilityId),
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
    onSuccess: () => invalidateLiabilityValues(qc, householdId, liabilityId),
  });
}

export function useDeleteLiabilityValue(householdId: string, liabilityId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (entryId: string) => {
      await apiClient.delete(`/households/${householdId}/liabilities/${liabilityId}/values/${entryId}`);
    },
    onSuccess: () => invalidateLiabilityValues(qc, householdId, liabilityId),
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
    onSuccess: () => invalidateAssetValues(qc, householdId, assetId),
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
    onSuccess: () => invalidateAssetValues(qc, householdId, assetId),
  });
}

export function useDeleteAssetValue(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (entryId: string) => {
      await apiClient.delete(`/households/${householdId}/assets/${assetId}/values/${entryId}`);
    },
    onSuccess: () => invalidateAssetValues(qc, householdId, assetId),
  });
}

export interface NamedValuesAtDate {
  assets: Record<string, string>;
  liabilities: Record<string, string>;
  // The cash estimate at the date, or null when no adjustment series exists (carry over as before).
  cash: string | null;
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
    onSuccess: () => invalidateSnapshots(qc, householdId),
  });
}

export function useUpdateSnapshot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: SnapshotInput }) =>
      (await apiClient.patch<Snapshot>(`/households/${householdId}/snapshots/${id}`, input)).data,
    onSuccess: () => invalidateSnapshots(qc, householdId),
  });
}

export function useDeleteSnapshot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/snapshots/${id}`);
    },
    onSuccess: () => invalidateSnapshots(qc, householdId),
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
    onSuccess: () => invalidateMovements(qc, householdId),
  });
}

export function useUpdateMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: MovementInput }) =>
      (await apiClient.patch<Movement>(`/households/${householdId}/movements/${id}`, input)).data,
    onSuccess: () => invalidateMovements(qc, householdId),
  });
}

export function useDeleteMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/movements/${id}`);
    },
    onSuccess: () => invalidateMovements(qc, householdId),
  });
}

// --- Cash: a dated adjustment series (source of truth) + a flow-based estimate between them. ---

export interface CashEstimate {
  date: string;
  anchorDate: string | null;
  anchorAmount: string | null;
  netTransactions: string;
  netLendings: string;
  netMovements: string;
  netFlows: string;
  // Null when there is no adjustment yet (cash has no series; it carries over as before).
  estimate: string | null;
}

export interface CashSettings {
  includeTransactions: boolean;
  includeLendings: boolean;
  includeMovements: boolean;
}

function invalidateLiabilityValues(qc: ReturnType<typeof useQueryClient>, householdId: string, liabilityId: string) {
  void qc.invalidateQueries({ queryKey: ["liability-values", householdId, liabilityId] });
  void qc.invalidateQueries({ queryKey: ["liabilities", householdId] });
}

function invalidateAssetValues(qc: ReturnType<typeof useQueryClient>, householdId: string, assetId: string) {
  void qc.invalidateQueries({ queryKey: ["asset-values", householdId, assetId] });
  void qc.invalidateQueries({ queryKey: ["assets", householdId] });
}

function invalidateSnapshots(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  void qc.invalidateQueries({ queryKey: ["snapshots", householdId] });
  void qc.invalidateQueries({ queryKey: ["snapshot-prefill", householdId] });
  // FIRE's starting value comes from the latest snapshot's qualifying assets.
  void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
}

function invalidateMovements(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  void qc.invalidateQueries({ queryKey: ["movements", householdId] });
  // The cumulative-contributions overlay in FIRE is built from movements.
  void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
}

function invalidateCash(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  void qc.invalidateQueries({ queryKey: ["cash-adjustments", householdId] });
  void qc.invalidateQueries({ queryKey: ["cash-estimate", householdId] });
  // The snapshot form prefills cash from the estimate (per-date named-values query).
  void qc.invalidateQueries({ queryKey: ["named-values", householdId] });
}

export function useCashAdjustments(householdId: string) {
  return useQuery({
    queryKey: ["cash-adjustments", householdId],
    queryFn: async () => {
      const raw = (await apiClient.get<{ id: string; adjustmentDate: string; amount: string }[]>(
        `/households/${householdId}/cash/adjustments`,
      )).data;
      return raw.map((e): ValueEntry => ({ id: e.id, date: e.adjustmentDate, value: e.amount }));
    },
  });
}

export function useAddCashAdjustment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ date, value }: { date: string; value: string }) =>
      (await apiClient.post(`/households/${householdId}/cash/adjustments`, { adjustmentDate: date, amount: value })).data,
    onSuccess: () => invalidateCash(qc, householdId),
  });
}

export function useUpdateCashAdjustment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ entryId, date, value }: { entryId: string; date: string; value: string }) =>
      (await apiClient.patch(`/households/${householdId}/cash/adjustments/${entryId}`, { adjustmentDate: date, amount: value })).data,
    onSuccess: () => invalidateCash(qc, householdId),
  });
}

export function useDeleteCashAdjustment(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (entryId: string) => {
      await apiClient.delete(`/households/${householdId}/cash/adjustments/${entryId}`);
    },
    onSuccess: () => invalidateCash(qc, householdId),
  });
}

export function useCashEstimate(householdId: string, date: string) {
  return useQuery({
    queryKey: ["cash-estimate", householdId, date],
    enabled: !!date,
    queryFn: async () =>
      (await apiClient.get<CashEstimate>(`/households/${householdId}/cash/estimate`, { params: { date } })).data,
  });
}

export function useCashSettings(householdId: string) {
  return useQuery({
    queryKey: ["cash-settings", householdId],
    queryFn: async () => (await apiClient.get<CashSettings>(`/households/${householdId}/cash/settings`)).data,
  });
}

export function useUpdateCashSettings(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CashSettings) =>
      (await apiClient.put<CashSettings>(`/households/${householdId}/cash/settings`, input)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["cash-settings", householdId] });
      void qc.invalidateQueries({ queryKey: ["cash-estimate", householdId] });
      void qc.invalidateQueries({ queryKey: ["named-values", householdId] });
    },
  });
}
