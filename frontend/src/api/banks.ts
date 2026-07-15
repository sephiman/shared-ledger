import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type ConnectionStatus = "active" | "expired" | "suspended" | "error";
export type SyncFrequency = "daily" | "twice_daily";
export type MovementStatus = "pending" | "confirmed" | "rejected";
export type Direction = "income" | "expense";
export type RuleField = "counterparty" | "description" | "amount";
export type RuleOp = "equals" | "contains" | "range";
export type RuleSource = "manual" | "learned";
export type SyncRunStatus = "success" | "error";

export interface PendingConnectionCount {
  connectionId: string;
  /** Connection label, falling back to the bank name when unlabelled. */
  label: string;
  count: number;
}

export interface PendingCount {
  count: number;
  /** Pending per bank connection, largest inbox first. */
  byConnection: PendingConnectionCount[];
}

export interface BankConfig {
  featureEnabled: boolean;
  connectionCount: number;
  /** Upcoming background-sync run times as ISO instants; rendered in the viewer's timezone. */
  nextSyncTimes: string[];
}

export interface Aspsp {
  name: string;
  country: string;
  logoUrl: string | null;
}

export interface BankAccount {
  id: string;
  ibanMasked: string | null;
  name: string | null;
  currency: string | null;
}

export interface BankConnection {
  id: string;
  provider: string;
  aspspName: string;
  aspspCountry: string;
  label: string | null;
  status: ConnectionStatus;
  consentExpiresAt: string | null;
  lastSyncedAt: string | null;
  ingestionEnabled: boolean;
  syncFrequency: SyncFrequency;
  accounts: BankAccount[];
  lastSyncStatus: SyncRunStatus | null;
  lastSyncError: string | null;
}

export interface PendingMovement {
  id: string;
  connectionId: string;
  connectionLabel: string | null;
  aspspName: string;
  accountId: string;
  accountName: string | null;
  bookingDate: string;
  valueDate: string | null;
  direction: Direction;
  amount: string;
  originalAmount: string | null;
  originalCurrency: string | null;
  counterparty: string | null;
  description: string | null;
  reference: string | null;
  status: MovementStatus;
  suggestedCategoryCode: string | null;
  createdTransactionId: string | null;
  createdMovementId: string | null;
  possibleDuplicate: boolean;
}

export interface CategorizationRule {
  id: string;
  matchField: RuleField;
  matchOp: RuleOp;
  matchValue: string;
  categoryCode: string;
  direction: Direction;
  priority: number;
  source: RuleSource;
  createdAt: string;
}

export interface StartLinkInput {
  aspspName: string;
  country: string;
  label?: string | null;
  relinkConnectionId?: string | null;
}

export interface ConfirmMovementInput {
  categoryCode?: string | null;
  direction?: Direction | null;
  note?: string | null;
  saveRule?: boolean;
}

export type MovementType = "contribution" | "withdrawal" | "debt_payment";

export interface ConfirmAsMovementInput {
  type: MovementType;
  assetClassCode?: string | null;
  liabilityId?: string | null;
  note?: string | null;
}

export interface EditMovementInput {
  suggestedCategoryCode?: string | null;
  direction?: Direction | null;
  description?: string | null;
}

export interface RuleInput {
  matchField: RuleField;
  matchOp: RuleOp;
  matchValue: string;
  categoryCode: string;
  direction: Direction;
  priority?: number;
}

export interface BatchResult {
  confirmed: number;
  rejected: number;
  restored: number;
  skipped: string[];
}

export interface ConfirmBatchItem {
  id: string;
  categoryCode?: string | null;
  direction?: Direction | null;
}

export interface ApplyRulesResult {
  categorized: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

const base = (householdId: string) => `/households/${householdId}/banks`;

/** Confirming/rejecting changes the real ledger, so invalidate transaction-derived data too. */
function invalidateAll(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  void qc.invalidateQueries({ queryKey: ["banks", householdId] });
  void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
  void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
  void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
}

/**
 * Sync and link kick off background server work (they return 202 before movements are ingested),
 * so a single immediate invalidation refetches too early. Re-invalidate a few times to catch the
 * background completion; the count/badge also poll on their own as a backstop.
 */
function invalidateBanksSoon(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  [1500, 4000, 9000].forEach((ms) =>
    setTimeout(() => void qc.invalidateQueries({ queryKey: ["banks", householdId] }), ms),
  );
}

// --- Queries ---------------------------------------------------------------------------------

export function useBankConfig(householdId: string) {
  return useQuery({
    queryKey: ["banks", householdId, "config"],
    queryFn: async () => (await apiClient.get<BankConfig>(`${base(householdId)}/config`)).data,
  });
}

export function useAspsps(householdId: string, country: string, enabled: boolean) {
  return useQuery({
    queryKey: ["banks", householdId, "aspsps", country],
    queryFn: async () =>
      (await apiClient.get<Aspsp[]>(`${base(householdId)}/aspsps`, { params: { country } })).data,
    enabled: enabled && country.trim().length === 2,
    staleTime: 1000 * 60 * 30,
  });
}

export function useBankConnections(householdId: string, enabled = true) {
  return useQuery({
    queryKey: ["banks", householdId, "connections"],
    queryFn: async () => (await apiClient.get<BankConnection[]>(`${base(householdId)}/connections`)).data,
    enabled,
  });
}

export type PendingCategorisation = "categorized" | "uncategorized";

export function usePendingMovements(
  householdId: string,
  filters: {
    status: MovementStatus;
    connectionId?: string;
    // Server-side filters over the full dataset (not just the loaded page): free-text search,
    // categorisation state, and possible-duplicates only. Omit to disable each.
    search?: string;
    categorisation?: PendingCategorisation;
    duplicatesOnly?: boolean;
    page?: number;
    size?: number;
  },
  enabled = true,
) {
  return useQuery({
    queryKey: ["banks", householdId, "pending", filters],
    queryFn: async () =>
      (await apiClient.get<Page<PendingMovement>>(`${base(householdId)}/pending`, { params: filters })).data,
    enabled,
  });
}

export function usePendingCount(householdId: string, enabled = true) {
  return useQuery({
    queryKey: ["banks", householdId, "pending-count"],
    queryFn: async () =>
      (await apiClient.get<PendingCount>(`${base(householdId)}/pending/count`)).data,
    enabled,
    // Poll so the tab counter / nav badge pick up background syncs (and other members' changes).
    refetchInterval: 20_000,
    refetchOnWindowFocus: true,
  });
}

export function useCategorizationRules(householdId: string) {
  return useQuery({
    queryKey: ["banks", householdId, "rules"],
    queryFn: async () => (await apiClient.get<CategorizationRule[]>(`${base(householdId)}/rules`)).data,
  });
}

// --- Connection mutations --------------------------------------------------------------------

export function useStartLink(householdId: string) {
  return useMutation({
    mutationFn: async (input: StartLinkInput) =>
      (await apiClient.post<{ authUrl: string }>(`${base(householdId)}/connections/start`, input)).data,
    meta: { silentSuccess: true },
  });
}

export function useCompleteLink(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { code: string; state: string }) =>
      (await apiClient.post<BankConnection>(`${base(householdId)}/connections/complete`, input)).data,
    onSuccess: () => { invalidateAll(qc, householdId); invalidateBanksSoon(qc, householdId); },
  });
}

export function useSyncConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`${base(householdId)}/connections/${id}/sync`);
    },
    // Sync is async (202): re-invalidate a few times to catch the background ingest.
    onSuccess: () => { invalidateAll(qc, householdId); invalidateBanksSoon(qc, householdId); },
  });
}

export function useUpdateConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: Partial<Pick<BankConnection, "label" | "ingestionEnabled" | "syncFrequency">> }) =>
      (await apiClient.patch<BankConnection>(`${base(householdId)}/connections/${id}`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId] }),
  });
}

export function useDeleteConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`${base(householdId)}/connections/${id}`);
    },
    onSuccess: () => invalidateAll(qc, householdId),
  });
}

// --- Pending-movement mutations --------------------------------------------------------------

export function useConfirmMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: ConfirmMovementInput }) =>
      (await apiClient.post<PendingMovement>(`${base(householdId)}/pending/${id}/confirm`, input)).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

/** Confirm a pending item as a net-worth movement; also refresh movement/networth-derived data. */
export function useConfirmAsMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: ConfirmAsMovementInput }) =>
      (await apiClient.post<PendingMovement>(`${base(householdId)}/pending/${id}/confirm-as-movement`, input)).data,
    onSuccess: () => {
      invalidateAll(qc, householdId);
      void qc.invalidateQueries({ queryKey: ["movements", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
      void qc.invalidateQueries({ queryKey: ["cash-estimate", householdId] });
    },
    meta: { silentSuccess: true },
  });
}

export function useConfirmBatch(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (items: ConfirmBatchItem[]) =>
      (await apiClient.post<BatchResult>(`${base(householdId)}/pending/confirm-batch`, { items })).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

export function useRejectMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<PendingMovement>(`${base(householdId)}/pending/${id}/reject`)).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

export function useRejectBatch(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) =>
      (await apiClient.post<BatchResult>(`${base(householdId)}/pending/reject-batch`, { ids })).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

export function useRestoreMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<PendingMovement>(`${base(householdId)}/pending/${id}/restore`)).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

export function useRestoreBatch(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) =>
      (await apiClient.post<BatchResult>(`${base(householdId)}/pending/restore-batch`, { ids })).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

/** Run categorisation rules over the uncategorized pending movements (fills suggestions only). */
export function useApplyRules(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () =>
      (await apiClient.post<ApplyRulesResult>(`${base(householdId)}/pending/apply-rules`)).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

export function useEditMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: EditMovementInput }) =>
      (await apiClient.patch<PendingMovement>(`${base(householdId)}/pending/${id}`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId] }),
    meta: { silentSuccess: true },
  });
}

// --- Rule mutations --------------------------------------------------------------------------

export function useCreateRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: RuleInput) =>
      (await apiClient.post<CategorizationRule>(`${base(householdId)}/rules`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId, "rules"] }),
  });
}

export function useUpdateRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: RuleInput }) =>
      (await apiClient.patch<CategorizationRule>(`${base(householdId)}/rules/${id}`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId, "rules"] }),
  });
}

export function useDeleteRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`${base(householdId)}/rules/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId, "rules"] }),
  });
}

export function useDeleteRules(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids: string[]) =>
      (await apiClient.post<{ deleted: number }>(`${base(householdId)}/rules/delete-batch`, { ids })).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId, "rules"] }),
  });
}
