import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  useBankConnections,
  useConfirmBatch,
  useConfirmMovement,
  useEditMovement,
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
import { Badge, Button, Card, CardBody, CardHeader, Input, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { addDaysIso, formatDate } from "@/lib/dates";
import { showToast } from "@/lib/toastBus";

type GroupBy = "none" | "connection" | "category";

// The API caps a page at 200; we load that once and do search/group/select/paginate client-side.
const FETCH_SIZE = 200;
const CLIENT_PAGE = 25;
// Mirrors the backend's DUPLICATE_WINDOW_DAYS: a possible-duplicate match can be a few days off the
// booking date, so "view match" must filter to the same ±window, not just the exact day.
const DUPLICATE_WINDOW_DAYS = 3;

export function PendingInbox({ householdId, currency, locale }: { householdId: string; currency: string; locale: string }) {
  const { t } = useTranslation();

  const [status, setStatus] = useState<MovementStatus>("pending");
  const [connectionId, setConnectionId] = useState("");
  const [search, setSearch] = useState("");
  const [groupBy, setGroupBy] = useState<GroupBy>("none");
  const [cpage, setCpage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [categoryById, setCategoryById] = useState<Record<string, string>>({});
  const [directionById, setDirectionById] = useState<Record<string, Direction>>({});

  const { data: connections = [] } = useBankConnections(householdId);
  const { data, isLoading } = usePendingMovements(householdId, {
    status,
    connectionId: connectionId || undefined,
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
  const editMovement = useEditMovement(householdId);

  const editable = status === "pending";
  // Rejected items can be sent back to the inbox; enable row selection + a restore action for them.
  const restorable = status === "rejected";
  const selectable = editable || restorable;
  const loaded = data?.items ?? [];
  const total = data?.total ?? 0;
  const truncated = total > loaded.length;

  const q = search.trim().toLowerCase();
  const filtered = useMemo(
    () =>
      q
        ? loaded.filter((m) =>
            [m.counterparty, m.description, m.reference].some((v) => (v ?? "").toLowerCase().includes(q)),
          )
        : loaded,
    [loaded, q],
  );

  const resolveCategory = (m: PendingMovement) => categoryById[m.id] ?? m.suggestedCategoryCode ?? "";
  const resolveDirection = (m: PendingMovement): Direction => directionById[m.id] ?? m.direction;

  const selectedIds = useMemo(() => filtered.filter((m) => selected.has(m.id)).map((m) => m.id), [filtered, selected]);
  const allSelected = filtered.length > 0 && filtered.every((m) => selected.has(m.id));
  const someSelected = filtered.some((m) => selected.has(m.id));

  const resetView = () => { setCpage(0); setSelected(new Set()); };

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
    const items = selectedIds.map((id) => {
      const m = filtered.find((x) => x.id === id)!;
      return { id, categoryCode: resolveCategory(m) || null, direction: resolveDirection(m) };
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
      { id: m.id, input: { categoryCode: resolveCategory(m), direction: resolveDirection(m), saveRule: true } },
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
      onConfirm={() => confirmRow(m)}
      onReject={() => rejectRow(m)}
      onRestore={() => restoreRow(m)}
      onSaveDescription={(desc) => editMovement.mutate({ id: m.id, input: { description: desc } })}
      busy={confirmOne.isPending || rejectOne.isPending || restoreOne.isPending}
    />
  );

  return (
    <div className="space-y-3">
      {/* Filter bar */}
      <Card>
        <CardBody className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
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
              <Button variant="secondary" disabled={rejectBatch.isPending} onClick={rejectSelected}>{t("banks.reject")}</Button>
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
  movement, currency, locale, categories, editable, selectable, restorable, selected, onToggle,
  categoryValue, onCategoryChange, direction, onDirectionChange, onConfirm, onReject, onRestore, onSaveDescription, busy,
}: {
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
  onConfirm: () => void;
  onReject: () => void;
  onRestore: () => void;
  onSaveDescription: (description: string) => void;
  busy: boolean;
}) {
  const { t, i18n } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [desc, setDesc] = useState(movement.description ?? "");

  const options = categories.filter((c) => c.kind === direction);
  const income = direction === "income";
  const source = [movement.connectionLabel ?? movement.aspspName, movement.accountName].filter(Boolean).join(" · ");
  const secondary = movement.description ?? movement.reference;

  return (
    <li className="rounded-md border border-border p-3 dark:border-gray-700">
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
          </p>
          {secondary && <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">{secondary}</p>}
        </div>
        <div className={`shrink-0 text-right font-semibold ${income ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400"}`}>
          {income ? "+" : "−"}{formatMoney(movement.amount, currency, locale)}
        </div>
      </div>

      {editable && (
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
          <Button disabled={!categoryValue || busy} onClick={onConfirm}>{t("banks.confirm")}</Button>
          <Button variant="ghost" onClick={() => setEditing((v) => !v)}>{t("common.edit")}</Button>
          <Button variant="secondary" disabled={busy} onClick={onReject}>{t("banks.reject")}</Button>
        </div>
      )}

      {editable && editing && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Input value={desc} onChange={(e) => setDesc(e.target.value)} placeholder={t("common.description")} className="max-w-[24rem]" />
          <Button onClick={() => { onSaveDescription(desc); setEditing(false); }}>{t("common.save")}</Button>
          <Button variant="ghost" onClick={() => { setDesc(movement.description ?? ""); setEditing(false); }}>{t("common.cancel")}</Button>
        </div>
      )}

      {!editable && movement.suggestedCategoryCode && (
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">
          {t("common.category")}: {categoryLabelByCode(movement.suggestedCategoryCode, categories, t)}
        </p>
      )}

      {restorable && (
        <div className="mt-3">
          <Button variant="secondary" disabled={busy} onClick={onRestore}>{t("banks.restore")}</Button>
        </div>
      )}
    </li>
  );
}
