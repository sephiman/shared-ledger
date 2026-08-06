import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  useApplyRules,
  useBankConnections,
  useConfirmBatch,
  useConfirmMovement,
  usePendingMovements,
  useRejectBatch,
  useRejectMovement,
  useRestoreBatch,
  useRestoreMovement,
  type Direction,
  type MovementStatus,
  type PendingMovement,
} from "@/api/banks";
import { useCategories, type Category } from "@/api/catalog";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { Badge, Button, Card, CardBody, CardHeader, Chip, Input, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { addDaysIso, formatDate } from "@/lib/dates";
import { showToast } from "@/lib/toastBus";
import {
  PENDING_FILTER_DEFAULTS,
  hasActivePendingFilters,
  type CategorisationState,
  type GroupBy,
} from "./pendingFilters";
import { bankDescription } from "./bankDescription";
import { MarkAsMovementDialog } from "./MarkAsMovementDialog";
import { ReplaceDuplicateDialog } from "./ReplaceDuplicateDialog";
import { SplitMovementDialog } from "./SplitMovementDialog";

// Search / categorisation / duplicates are filtered server-side over the full dataset. The API caps
// a page at 200; we load that (already-filtered) page once and do group/select/paginate client-side.
const FETCH_SIZE = 200;
const CLIENT_PAGE = 25;
// Debounce the free-text search so typing doesn't fire a server request per keystroke.
const SEARCH_DEBOUNCE_MS = 300;
// Mirrors the backend's DUPLICATE_WINDOW_DAYS: a possible-duplicate match can be a few days off the
// booking date, so "view match" must filter to the same ±window, not just the exact day.
const DUPLICATE_WINDOW_DAYS = 3;

// Reject reads as mildly destructive: a bordered `secondary` button with a red tint (not a full
// danger fill). Shared by the per-row and bulk Reject buttons so they match.
const REJECT_BUTTON_CLASS =
  "border-red-300 text-red-600 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-900/30";

export function PendingInbox({ householdId, currency, locale }: { householdId: string; currency: string; locale: string }) {
  const { t } = useTranslation();

  const [status, setStatus] = useState<MovementStatus>(PENDING_FILTER_DEFAULTS.status);
  const [connectionId, setConnectionId] = useState(PENDING_FILTER_DEFAULTS.connectionId);
  const [search, setSearch] = useState(PENDING_FILTER_DEFAULTS.search);
  const [groupBy, setGroupBy] = useState<GroupBy>(PENDING_FILTER_DEFAULTS.groupBy);
  const [categorisationState, setCategorisationState] = useState<CategorisationState>(PENDING_FILTER_DEFAULTS.categorisationState);
  const [duplicatesOnly, setDuplicatesOnly] = useState(PENDING_FILTER_DEFAULTS.duplicatesOnly);
  const [cpage, setCpage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [categoryById, setCategoryById] = useState<Record<string, string>>({});
  const [directionById, setDirectionById] = useState<Record<string, Direction>>({});
  // Like category and direction: local until the row is confirmed, replaced or split — never per keystroke.
  const [descriptionById, setDescriptionById] = useState<Record<string, string>>({});

  // Debounced mirror of `search`; the input updates instantly, the server query follows on a delay.
  const [debouncedSearch, setDebouncedSearch] = useState(search);
  useEffect(() => {
    const handle = window.setTimeout(() => setDebouncedSearch(search), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [search]);

  const { data: connections = [] } = useBankConnections(householdId);
  const { data, isLoading } = usePendingMovements(householdId, {
    status,
    connectionId: connectionId || undefined,
    search: debouncedSearch.trim() || undefined,
    categorisation: categorisationState === "all" ? undefined : categorisationState,
    // The possible-duplicate flag is only computed for pending items, so this bites only there.
    duplicatesOnly: status === "pending" && duplicatesOnly ? true : undefined,
    page: 0,
    size: FETCH_SIZE,
  });
  const { data: categories = [] } = useCategories(householdId);

  const confirmOne = useConfirmMovement(householdId);
  const rejectOne = useRejectMovement(householdId);
  const confirmBatch = useConfirmBatch(householdId);
  const rejectBatch = useRejectBatch(householdId);
  const restoreOne = useRestoreMovement(householdId);
  const restoreBatch = useRestoreBatch(householdId);
  const applyRules = useApplyRules(householdId);

  const editable = status === "pending";
  // Rejected items can be sent back to the inbox; enable row selection + a restore action for them.
  const restorable = status === "rejected";
  const selectable = editable || restorable;
  const loaded = data?.items ?? [];
  const total = data?.total ?? 0;
  const truncated = total > loaded.length;

  const resolveCategory = (m: PendingMovement) => categoryById[m.id] ?? m.suggestedCategoryCode ?? "";
  const resolveDirection = (m: PendingMovement): Direction => directionById[m.id] ?? m.direction;
  // Untouched rows fall back to what the backend would store anyway, so confirming as-is changes nothing.
  const resolveDescription = (m: PendingMovement) => descriptionById[m.id] ?? bankDescription(m);

  // Search / categorisation / duplicates are already applied server-side, so the loaded page is the
  // filtered set; grouping, selection and client pagination run over it.
  const filtered = loaded;

  const selectedIds = useMemo(() => filtered.filter((m) => selected.has(m.id)).map((m) => m.id), [filtered, selected]);
  const allSelected = filtered.length > 0 && filtered.every((m) => selected.has(m.id));
  const someSelected = filtered.some((m) => selected.has(m.id));

  const resetView = () => { setCpage(0); setSelected(new Set()); };

  const filterState = { status, connectionId, search, groupBy, categorisationState, duplicatesOnly };

  const clearFilters = () => {
    setStatus(PENDING_FILTER_DEFAULTS.status);
    setConnectionId(PENDING_FILTER_DEFAULTS.connectionId);
    setSearch(PENDING_FILTER_DEFAULTS.search);
    setGroupBy(PENDING_FILTER_DEFAULTS.groupBy);
    setCategorisationState(PENDING_FILTER_DEFAULTS.categorisationState);
    setDuplicatesOnly(PENDING_FILTER_DEFAULTS.duplicatesOnly);
    resetView();
  };

  // Fills the suggested category for uncategorized pending movements; they stay pending afterwards.
  const runApplyRules = () =>
    applyRules.mutate(undefined, {
      onSuccess: (res) => showToast(t("banks.rules_applied", { count: res.categorized }), "success"),
    });

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });

  const toggleAll = () =>
    setSelected((prev) => {
      if (filtered.every((m) => prev.has(m.id))) {
        const next = new Set(prev);
        filtered.forEach((m) => next.delete(m.id));
        return next;
      }
      return new Set([...prev, ...filtered.map((m) => m.id)]);
    });

  const setDirection = (m: PendingMovement, dir: Direction) =>
    setDirectionById((prev) => {
      // If the currently chosen category no longer matches the new direction, drop it.
      const chosen = categoryById[m.id] ?? m.suggestedCategoryCode;
      const kind = categories.find((c) => c.code === chosen)?.kind;
      if (chosen && kind && kind !== dir) setCategoryById((cs) => { const n = { ...cs }; delete n[m.id]; return n; });
      return { ...prev, [m.id]: dir };
    });

  const applyCategoryToSelected = (code: string) => {
    const kind = categories.find((c) => c.code === code)?.kind;
    if (!kind) return;
    const targets = filtered.filter((m) => selected.has(m.id) && resolveDirection(m) === kind);
    const skipped = filtered.filter((m) => selected.has(m.id)).length - targets.length;
    if (targets.length > 0) {
      const ids = new Set(targets.map((m) => m.id));
      setCategoryById((prev) => {
        const next = { ...prev };
        for (const id of ids) next[id] = code;
        return next;
      });
      showToast(t("banks.category_applied", { count: targets.length }), "success");
    }
    if (skipped > 0) showToast(t("banks.category_skipped_direction", { count: skipped }), "error");
  };

  const confirmSelected = () => {
    // Each row contributes whatever its inputs hold right now.
    const items = selectedIds.map((id) => {
      const m = filtered.find((x) => x.id === id)!;
      return {
        id,
        categoryCode: resolveCategory(m) || null,
        direction: resolveDirection(m),
        note: resolveDescription(m) || null,
      };
    });
    confirmBatch.mutate(items, {
      onSuccess: (res) => {
        setSelected(new Set());
        if (res.confirmed > 0) showToast(t("banks.confirmed_toast", { count: res.confirmed }), "success");
        if (res.skipped.length > 0) showToast(t("banks.batch_skipped", { count: res.skipped.length }), "error");
      },
    });
  };

  const rejectSelected = () => {
    rejectBatch.mutate(selectedIds, {
      onSuccess: (res) => { setSelected(new Set()); showToast(t("banks.rejected_toast", { count: res.rejected }), "success"); },
    });
  };

  const confirmRow = (m: PendingMovement) =>
    confirmOne.mutate(
      {
        id: m.id,
        input: {
          categoryCode: resolveCategory(m),
          direction: resolveDirection(m),
          note: resolveDescription(m) || null,
          saveRule: true,
        },
      },
      { onSuccess: () => showToast(t("banks.confirmed_toast", { count: 1 }), "success") },
    );

  const rejectRow = (m: PendingMovement) =>
    rejectOne.mutate(m.id, { onSuccess: () => showToast(t("banks.rejected_toast", { count: 1 }), "success") });

  const restoreSelected = () => {
    restoreBatch.mutate(selectedIds, {
      onSuccess: (res) => { setSelected(new Set()); showToast(t("banks.restored_toast", { count: res.restored }), "success"); },
    });
  };

  const restoreRow = (m: PendingMovement) =>
    restoreOne.mutate(m.id, { onSuccess: () => showToast(t("banks.restored_toast", { count: 1 }), "success") });

  const groups = useMemo(() => buildGroups(filtered, groupBy, categories, t, resolveCategory), [filtered, groupBy, categories, categoryById]);
  const pageCount = Math.max(1, Math.ceil(filtered.length / CLIENT_PAGE));
  const pageItems = filtered.slice(cpage * CLIENT_PAGE, cpage * CLIENT_PAGE + CLIENT_PAGE);

  const renderRow = (m: PendingMovement) => (
    <MovementRow
      key={m.id}
      householdId={householdId}
      movement={m}
      currency={currency}
      locale={locale}
      categories={categories}
      editable={editable}
      selectable={selectable}
      restorable={restorable}
      selected={selected.has(m.id)}
      onToggle={() => toggle(m.id)}
      categoryValue={resolveCategory(m)}
      onCategoryChange={(v) => setCategoryById((prev) => ({ ...prev, [m.id]: v }))}
      direction={resolveDirection(m)}
      onDirectionChange={(d) => setDirection(m, d)}
      descriptionValue={resolveDescription(m)}
      onDescriptionChange={(v) => setDescriptionById((prev) => ({ ...prev, [m.id]: v }))}
      onConfirm={() => confirmRow(m)}
      onReject={() => rejectRow(m)}
      onRestore={() => restoreRow(m)}
      busy={confirmOne.isPending || rejectOne.isPending || restoreOne.isPending}
    />
  );

  return (
    <div className="space-y-3">
      {/* Filter bar */}
      <Card>
        <CardBody className="space-y-2">
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
            <Select value={status} onChange={(e) => { setStatus(e.target.value as MovementStatus); resetView(); }}>
              <option value="pending">{t("banks.filter_pending")}</option>
              <option value="rejected">{t("banks.filter_rejected")}</option>
              <option value="confirmed">{t("banks.filter_confirmed")}</option>
            </Select>
            <Select value={connectionId} onChange={(e) => { setConnectionId(e.target.value); resetView(); }}>
              <option value="">{t("banks.all_connections")}</option>
              {connections.map((c) => (
                <option key={c.id} value={c.id}>{c.label ?? c.aspspName}</option>
              ))}
            </Select>
            <Input
              value={search}
              placeholder={t("banks.search_placeholder")}
              onChange={(e) => { setSearch(e.target.value); setCpage(0); }}
            />
            <Select value={groupBy} onChange={(e) => { setGroupBy(e.target.value as GroupBy); resetView(); }}>
              <option value="none">{t("banks.group_none")}</option>
              <option value="connection">{t("banks.group_by_connection")}</option>
              <option value="category">{t("banks.group_by_category")}</option>
            </Select>
            <Select value={categorisationState} onChange={(e) => { setCategorisationState(e.target.value as CategorisationState); setCpage(0); }}>
              <option value="all">{t("banks.catstate_all")}</option>
              <option value="uncategorized">{t("banks.catstate_uncategorized")}</option>
              <option value="categorized">{t("banks.catstate_categorized")}</option>
            </Select>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {editable && (
              <Chip active={duplicatesOnly} onClick={() => { setDuplicatesOnly((v) => !v); setCpage(0); }}>
                {t("banks.only_duplicates")}
              </Chip>
            )}
            <div className="grow" />
            {editable && (
              <Button variant="secondary" disabled={applyRules.isPending} onClick={runApplyRules}>
                {t("banks.apply_rules")}
              </Button>
            )}
            {hasActivePendingFilters(filterState) && (
              <Button variant="ghost" onClick={clearFilters}>{t("banks.clear_filters")}</Button>
            )}
          </div>
        </CardBody>
      </Card>

      {/* Bulk-action bar (sticky, only when something is selected) */}
      {editable && selectedIds.length > 0 && (
        <div className="sticky top-0 z-10">
          <Card className="border-primary/40 shadow">
            <CardBody className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium">{t("banks.n_selected", { count: selectedIds.length })}</span>
              <Select
                value=""
                className="max-w-[13rem]"
                aria-label={t("banks.set_expense_category")}
                onChange={(e) => { if (e.target.value) applyCategoryToSelected(e.target.value); }}
              >
                <option value="">{t("banks.set_expense_category")}</option>
                {categories.filter((c) => c.kind === "expense").sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t))).map((c) => (
                  <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                ))}
              </Select>
              <Select
                value=""
                className="max-w-[13rem]"
                aria-label={t("banks.set_income_category")}
                onChange={(e) => { if (e.target.value) applyCategoryToSelected(e.target.value); }}
              >
                <option value="">{t("banks.set_income_category")}</option>
                {categories.filter((c) => c.kind === "income").sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t))).map((c) => (
                  <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                ))}
              </Select>
              <Button disabled={confirmBatch.isPending} onClick={confirmSelected}>{t("banks.confirm")}</Button>
              <Button variant="secondary" className={REJECT_BUTTON_CLASS} disabled={rejectBatch.isPending} onClick={rejectSelected}>{t("banks.reject")}</Button>
              <Button variant="ghost" onClick={() => setSelected(new Set())}>{t("banks.clear_selection")}</Button>
            </CardBody>
          </Card>
        </div>
      )}

      {/* Bulk-restore bar (rejected view) */}
      {restorable && selectedIds.length > 0 && (
        <div className="sticky top-0 z-10">
          <Card className="border-primary/40 shadow">
            <CardBody className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium">{t("banks.n_selected", { count: selectedIds.length })}</span>
              <Button disabled={restoreBatch.isPending} onClick={restoreSelected}>{t("banks.restore")}</Button>
              <Button variant="ghost" onClick={() => setSelected(new Set())}>{t("banks.clear_selection")}</Button>
            </CardBody>
          </Card>
        </div>
      )}

      {/* List */}
      <Card>
        <CardHeader className="flex flex-wrap items-center justify-between gap-2">
          {selectable && filtered.length > 0 ? (
            <label className="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-300">
              <input
                type="checkbox"
                ref={(el) => { if (el) el.indeterminate = someSelected && !allSelected; }}
                checked={allSelected}
                onChange={toggleAll}
              />
              {t("banks.select_all_n", { count: filtered.length })}
            </label>
          ) : <span />}
          {truncated && (
            <span className="text-xs text-amber-600 dark:text-amber-400">{t("banks.truncated_note", { shown: loaded.length, total })}</span>
          )}
        </CardHeader>
        <CardBody>
          {isLoading ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : filtered.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("banks.inbox_empty")}</p>
          ) : groupBy === "none" ? (
            <>
              <ul className="space-y-2">{pageItems.map(renderRow)}</ul>
              {pageCount > 1 && (
                <div className="mt-4 flex items-center justify-between text-sm">
                  <span className="text-gray-500 dark:text-gray-400">{t("common.page_of", { page: cpage + 1, total: pageCount })}</span>
                  <div className="flex gap-2">
                    <Button variant="secondary" disabled={cpage === 0} onClick={() => setCpage((p) => Math.max(0, p - 1))}>{t("common.prev")}</Button>
                    <Button variant="secondary" disabled={cpage + 1 >= pageCount} onClick={() => setCpage((p) => p + 1)}>{t("common.next")}</Button>
                  </div>
                </div>
              )}
            </>
          ) : (
            <div className="space-y-4">
              {groups.map((g) => (
                <div key={g.key}>
                  <p className="mb-2 text-sm font-medium text-gray-700 dark:text-gray-200">{g.label} <span className="text-gray-400">({g.items.length})</span></p>
                  <ul className="space-y-2">{g.items.map(renderRow)}</ul>
                </div>
              ))}
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function buildGroups(
  items: PendingMovement[],
  groupBy: GroupBy,
  categories: Category[],
  t: ReturnType<typeof useTranslation>["t"],
  resolveCategory: (m: PendingMovement) => string,
): { key: string; label: string; items: PendingMovement[] }[] {
  if (groupBy === "none") return [{ key: "all", label: "", items }];
  const map = new Map<string, { label: string; items: PendingMovement[] }>();
  for (const m of items) {
    let key: string;
    let label: string;
    if (groupBy === "connection") {
      key = m.connectionId;
      label = m.connectionLabel ?? m.aspspName;
    } else {
      const code = resolveCategory(m);
      key = code || "__uncat__";
      label = code ? categoryLabelByCode(code, categories, t) : t("banks.uncategorized");
    }
    if (!map.has(key)) map.set(key, { label, items: [] });
    map.get(key)!.items.push(m);
  }
  return [...map.entries()].map(([key, v]) => ({ key, label: v.label, items: v.items })).sort((a, b) => a.label.localeCompare(b.label));
}

function MovementRow({
  householdId, movement, currency, locale, categories, editable, selectable, restorable, selected, onToggle,
  categoryValue, onCategoryChange, direction, onDirectionChange, descriptionValue, onDescriptionChange,
  onConfirm, onReject, onRestore, busy,
}: {
  householdId: string;
  movement: PendingMovement;
  currency: string;
  locale: string;
  categories: Category[];
  editable: boolean;
  selectable: boolean;
  restorable: boolean;
  selected: boolean;
  onToggle: () => void;
  categoryValue: string;
  onCategoryChange: (value: string) => void;
  direction: Direction;
  onDirectionChange: (d: Direction) => void;
  descriptionValue: string;
  onDescriptionChange: (value: string) => void;
  onConfirm: () => void;
  onReject: () => void;
  onRestore: () => void;
  busy: boolean;
}) {
  const { t, i18n } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const [markMovementOpen, setMarkMovementOpen] = useState(false);
  const [replaceOpen, setReplaceOpen] = useState(false);
  const [splitOpen, setSplitOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // Dismiss the ⋯ overflow menu on outside click or Escape (mirrors UserMenu).
  useEffect(() => {
    if (!menuOpen) return;
    const onPointer = (e: MouseEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setMenuOpen(false); };
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  const options = categories.filter((c) => c.kind === direction);
  const income = direction === "income";
  const source = [movement.connectionLabel ?? movement.aspspName, movement.accountName].filter(Boolean).join(" · ");
  // Editable rows show the description in an input instead, so only the reference is left to surface here.
  const secondary = editable ? null : movement.description ?? movement.reference;
  const splitCount = movement.createdTransactionIds.length;

  return (
    <li className="rounded-md border border-border p-3">
      <div className="flex items-start gap-3">
        {selectable && (
          <input type="checkbox" className="mt-1" checked={selected} onChange={onToggle} aria-label={t("banks.select_movement")} />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{movement.counterparty ?? t("banks.no_counterparty")}</span>
            {movement.possibleDuplicate && (
              <Badge tone="amber">
                {t("banks.possible_duplicate")}
                <Link to={`/transactions?from=${addDaysIso(movement.bookingDate, -DUPLICATE_WINDOW_DAYS)}&to=${addDaysIso(movement.bookingDate, DUPLICATE_WINDOW_DAYS)}`} className="underline">
                  {t("banks.view_match")}
                </Link>
              </Badge>
            )}
          </div>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
            <span>{formatDate(movement.bookingDate, i18n.language)}</span>
            {source && <Badge tone="sky">{source}</Badge>}
            {movement.originalCurrency && <span>{movement.originalAmount} {movement.originalCurrency}</span>}
            {/* Homeless once the description moves into an input, and sometimes all a transfer has. */}
            {editable && movement.reference && <span className="break-all">{movement.reference}</span>}
          </p>
          {secondary && <p className="mt-0.5 break-words text-sm text-gray-600 dark:text-gray-300">{secondary}</p>}
        </div>
        <div className={`shrink-0 text-right font-semibold ${income ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400"}`}>
          {income ? "+" : "−"}{formatMoney(movement.amount, currency, locale)}
        </div>
      </div>

      {editable && (
        // One row: inputs on the left, actions pushed to the right (they wrap as a unit on mobile).
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Select value={direction} onChange={(e) => onDirectionChange(e.target.value as Direction)} className="w-28">
            <option value="expense">{t("common.expense")}</option>
            <option value="income">{t("common.income")}</option>
          </Select>
          <Select value={categoryValue} onChange={(e) => onCategoryChange(e.target.value)} className="max-w-[15rem]">
            <option value="">{t("banks.uncategorized")}</option>
            {[...options].sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t))).map((c) => (
              <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
            ))}
          </Select>
          {/* Full width on phones so the whole description is readable; inline from `sm` up. */}
          <Input
            value={descriptionValue}
            placeholder={t("common.description")}
            aria-label={t("common.description")}
            className="w-full sm:w-auto sm:max-w-[18rem] sm:flex-1"
            onChange={(e) => onDescriptionChange(e.target.value)}
          />
          {/* Actions — Confirm primary, Reject a quieter bordered button, the rest under ⋯. */}
          <div className="ml-auto flex items-center gap-2">
            <Button disabled={!categoryValue || busy} onClick={onConfirm}>{t("banks.confirm")}</Button>
            <Button variant="secondary" className={REJECT_BUTTON_CLASS} disabled={busy} onClick={onReject}>{t("banks.reject")}</Button>
            {/* Only flagged rows have a transaction to reconcile with. Single-item by design. */}
            {movement.possibleDuplicate && (
              <Button variant="secondary" disabled={busy} onClick={() => setReplaceOpen(true)}>{t("banks.replace")}</Button>
            )}
            <div ref={menuRef} className="relative">
              <Button
                variant="secondary"
                className="px-3"
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                aria-label={t("common.more")}
                onClick={() => setMenuOpen((v) => !v)}
              >
                ⋯
              </Button>
              {menuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 z-20 mt-1 w-48 rounded-md border border-border bg-surface py-1 shadow-lg"
                >
                  {/* Single-item dialogs, both excluded from the batch actions. */}
                  <button
                    type="button"
                    role="menuitem"
                    disabled={busy}
                    onClick={() => { setMenuOpen(false); setSplitOpen(true); }}
                    className="block w-full px-3 py-2 text-left text-sm text-gray-700 hover:bg-item-hover disabled:opacity-50 dark:text-gray-200"
                  >
                    {t("banks.split")}
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    disabled={busy}
                    onClick={() => { setMenuOpen(false); setMarkMovementOpen(true); }}
                    className="block w-full px-3 py-2 text-left text-sm text-gray-700 hover:bg-item-hover disabled:opacity-50 dark:text-gray-200"
                  >
                    {t("banks.mark_as_movement")}
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {markMovementOpen && (
        <MarkAsMovementDialog
          open={markMovementOpen}
          householdId={householdId}
          movement={movement}
          currency={currency}
          locale={locale}
          onClose={() => setMarkMovementOpen(false)}
        />
      )}

      {replaceOpen && (
        <ReplaceDuplicateDialog
          open={replaceOpen}
          householdId={householdId}
          movement={movement}
          categories={categories}
          currency={currency}
          locale={locale}
          description={descriptionValue}
          onClose={() => setReplaceOpen(false)}
          onFallbackConfirm={onConfirm}
          canFallbackConfirm={!!categoryValue && !busy}
        />
      )}

      {splitOpen && (
        <SplitMovementDialog
          open={splitOpen}
          householdId={householdId}
          movement={movement}
          categories={categories}
          currency={currency}
          locale={locale}
          direction={direction}
          categoryCode={categoryValue}
          description={descriptionValue}
          onClose={() => setSplitOpen(false)}
        />
      )}

      {!editable && movement.suggestedCategoryCode && (
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
          {t("common.category")}: {categoryLabelByCode(movement.suggestedCategoryCode, categories, t)}
        </p>
      )}

      {/* Otherwise a confirmed split looks just like an ordinary confirm. */}
      {!editable && splitCount > 1 && (
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.split_into_n", { count: splitCount })}</p>
      )}

      {restorable && (
        <div className="mt-3">
          <Button variant="secondary" disabled={busy} onClick={onRestore}>{t("banks.restore")}</Button>
        </div>
      )}
    </li>
  );
}
