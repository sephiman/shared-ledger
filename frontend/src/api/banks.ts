import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type ConnectionStatus =
  | "active"
  | "expired"
  | "suspended"
  | "error"
  // Credential states: no household credentials, or a different application than this connection
  // was authorized under (re-link needed).
  | "credentials_required"
  | "credentials_mismatch";
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
  /** Whether THIS household configured its own Enable Banking application (no instance fallback). */
  credentialsConfigured: boolean;
  connectionCount: number;
  /** Upcoming background-sync run times as ISO instants; rendered in the viewer's timezone. */
  nextSyncTimes: string[];
}

export interface BankCredentials {
  appId: string | null;
  /** The private key is write-only; the form only learns whether one is stored. */
  privateKeyConfigured: boolean;
  /** What to register as the SCA redirect in the EB application. */
  redirectUrl: string;
  connectionCount: number;
  /** Connections authorized under a different application; they need re-linking. */
  mismatchedConnectionCount: number;
}

export interface BankCredentialsInput {
  appId: string;
  /** Omit or leave blank to keep the stored key. */
  privateKey?: string | null;
  /** Acknowledges the "N connections will need re-linking" warning. */
  confirm?: boolean;
}

export interface BankCredentialsTestResult {
  ok: boolean;
  message: string | null;
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
  /** Owners, plus the member who linked it. Every member sees every connection, manages only their own. */
  canManage: boolean;
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
  /** One for a confirm, Replace or merge (the merged items share it), N for a split — in both of the latter
   *  `createdTransactionId` is null. None while pending. */
  createdTransactionIds: string[];
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

/** A blank description falls back to the bank-derived one, server-side. */
export interface SplitPartInput {
  amount: string;
  categoryCode: string;
  description?: string | null;
}

/** Parts must sum to the movement's amount to the cent. `direction` applies to every part (null keeps the
 *  item's own): a single movement can't be part income and part expense. */
export interface SplitMovementInput {
  parts: SplitPartInput[];
  direction?: Direction | null;
}

/** One item of a merge. Null `direction` keeps the movement's stored one; the inbox sends the row's
 *  (possibly flipped) draft, since it decides this item's sign in the net. */
export interface MergeItemInput {
  id: string;
  direction?: Direction | null;
}

/** N items that are really one purchase become ONE transaction carrying their signed net — incomes add,
 *  expenses subtract — whose direction is the sign of that net. Neither is requested: a merge can't invent
 *  or drop money. Null date takes the earliest booking date, a blank description the joined bank-derived
 *  ones. A selection netting to zero is `CancelOutInput`'s job. */
export interface MergeMovementsInput {
  items: MergeItemInput[];
  categoryCode: string;
  date?: string | null;
  description?: string | null;
}

export interface MergeResult {
  transactionId: string;
  mergedCount: number;
}

/** The zero-net outcome: the items cancel each other out, so all of them are rejected and nothing is
 *  created. The directions travel along so the server can verify they really do cancel out. */
export interface CancelOutInput {
  items: MergeItemInput[];
}

/** A transaction a possible-duplicate item could replace; `bankLinked` ones are shown but not selectable. */
export interface DuplicateCandidate {
  transactionId: string;
  occurrenceDate: string;
  direction: Direction;
  categoryCode: string;
  amount: string;
  description: string | null;
  recurringTemplateId: string | null;
  bankLinked: boolean;
}

/** Null category/direction keep the transaction's own values; null description falls back to the bank's. */
export interface ReplaceTransactionInput {
  transactionId: string;
  categoryCode?: string | null;
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
  /** The row's description input; blank falls back to the bank's. */
  note?: string | null;
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

/** Sync and link return 202 before movements are ingested, so one immediate invalidation refetches too
 *  early. Re-invalidate a few times to catch the background completion; the badge also polls as a backstop. */
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

/** Owner-only: the household's Enable Banking application. Never returns the private key. */
export function useBankCredentials(householdId: string, enabled = true) {
  return useQuery({
    queryKey: ["banks", householdId, "credentials"],
    queryFn: async () =>
      (await apiClient.get<BankCredentials>(`${base(householdId)}/credentials`)).data,
    enabled,
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

/** Replace targets for one item, refetched on every dialog open so a since-deleted or since-linked
 *  transaction is reported instead of failing on submit. */
export function usePendingDuplicateCandidates(householdId: string, movementId: string, enabled = true) {
  return useQuery({
    queryKey: ["banks", householdId, "pending", movementId, "duplicate-candidates"],
    queryFn: async () =>
      (await apiClient.get<DuplicateCandidate[]>(`${base(householdId)}/pending/${movementId}/duplicate-candidates`)).data,
    enabled,
    staleTime: 0,
    gcTime: 0,
  });
}

export function useCategorizationRules(householdId: string) {
  return useQuery({
    queryKey: ["banks", householdId, "rules"],
    queryFn: async () => (await apiClient.get<CategorizationRule[]>(`${base(householdId)}/rules`)).data,
  });
}

// --- Credential mutations --------------------------------------------------------------------

/** Saving credentials changes which connections still work, so the whole bank tree is invalidated. */
export function useSaveBankCredentials(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: BankCredentialsInput) =>
      (await apiClient.put<BankCredentials>(`${base(householdId)}/credentials`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["banks", householdId] }),
    meta: { silentSuccess: true },
  });
}

export function useValidateBankCredentials(householdId: string) {
  return useMutation({
    mutationFn: async () =>
      (await apiClient.post<BankCredentialsTestResult>(`${base(householdId)}/credentials/validate`)).data,
    meta: { silentSuccess: true },
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

/** Updates the matched transaction in place, so the same derived data as a confirm has to be refreshed. */
export function useReplaceWithTransaction(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: ReplaceTransactionInput }) =>
      (await apiClient.post<PendingMovement>(`${base(householdId)}/pending/${id}/replace`, input)).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

/** Same derived data to refresh as a confirm. */
export function useSplitMovement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: SplitMovementInput }) =>
      (await apiClient.post<PendingMovement>(`${base(householdId)}/pending/${id}/split`, input)).data,
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

/** Confirms every merged item at once, so the same derived data as a batch confirm has to be refreshed. */
export function useMergeMovements(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: MergeMovementsInput) =>
      (await apiClient.post<MergeResult>(`${base(householdId)}/pending/merge`, input)).data,
    onSuccess: () => invalidateAll(qc, householdId),
    meta: { silentSuccess: true },
  });
}

/** Rejects a cancelling-out selection; creates nothing, so only the bank views need refreshing — but it
 *  goes through invalidateAll like its siblings rather than guessing which ones. */
export function useCancelOutMovements(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: CancelOutInput) =>
      (await apiClient.post<BatchResult>(`${base(householdId)}/pending/cancel-out`, input)).data,
    onSuccess: () => invalidateAll(qc, householdId),
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
