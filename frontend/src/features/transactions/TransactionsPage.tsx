import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useDeleteTransaction,
  useTransactions,
  type Transaction,
  type TransactionFilters,
} from "@/api/transactions";
import { useCategories } from "@/api/catalog";
import { useBankConnections, usePendingCount } from "@/api/banks";
import { Button, Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";
import { TabBar } from "@/components/ui/TabBar";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/dates";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { QuickAddForm } from "./QuickAddForm";
import { hasActiveTransactionFilters } from "./txFilters";
import { PendingInbox } from "@/features/banks/PendingInbox";

type PanelMode = { kind: "closed" } | { kind: "create" } | { kind: "edit"; tx: Transaction };

function buildExportQuery(filters: TransactionFilters): string {
  const keys: (keyof TransactionFilters)[] = ["from", "to", "direction", "categoryCode", "categoryGroup"];
  const params = new URLSearchParams();
  for (const k of keys) {
    const v = filters[k];
    if (v !== undefined && v !== null && v !== "") params.append(k, String(v));
  }
  const s = params.toString();
  return s ? `?${s}` : "";
}

function initialFiltersFromUrl(searchParams: URLSearchParams): TransactionFilters {
  const dir = searchParams.get("direction");
  return {
    size: 50,
    page: 0,
    from: searchParams.get("from") ?? undefined,
    to: searchParams.get("to") ?? undefined,
    direction: dir === "income" || dir === "expense" ? dir : undefined,
    categoryCode: searchParams.get("categoryCode") ?? undefined,
    categoryGroup: searchParams.get("categoryGroup") ?? undefined,
  };
}

export function TransactionsPage() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const [searchParams, setSearchParams] = useSearchParams();
  const [filters, setFilters] = useState<TransactionFilters>(() => initialFiltersFromUrl(searchParams));

  // The filter params can change while this page stays mounted (e.g. the "view match" link inside
  // the Pending tab navigates within /transactions, so the useState seed above never re-runs).
  // Re-apply them on change. Keyed on the individual values so a tab-only change doesn't clobber
  // filters the user edited by hand (manual edits don't touch the URL).
  const urlFrom = searchParams.get("from") ?? undefined;
  const urlTo = searchParams.get("to") ?? undefined;
  const urlDirection = searchParams.get("direction") ?? undefined;
  const urlCategoryCode = searchParams.get("categoryCode") ?? undefined;
  const urlCategoryGroup = searchParams.get("categoryGroup") ?? undefined;
  useEffect(() => {
    setFilters((f) => ({
      ...f,
      page: 0,
      from: urlFrom,
      to: urlTo,
      direction: urlDirection === "income" || urlDirection === "expense" ? urlDirection : undefined,
      categoryCode: urlCategoryCode,
      categoryGroup: urlCategoryGroup,
    }));
  }, [urlFrom, urlTo, urlDirection, urlCategoryCode, urlCategoryGroup]);
  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  const panelRef = useRef<HTMLDivElement | null>(null);
  const { data: page, isLoading } = useTransactions(household.householdId, filters);
  const { data: categories = [] } = useCategories(household.householdId);
  const del = useDeleteTransaction(household.householdId);

  // The "Pending" inbox sub-view only appears once at least one bank is linked.
  const { data: connections = [] } = useBankConnections(household.householdId);
  const showPending = connections.length > 0;
  const pendingCount = usePendingCount(household.householdId, showPending).data?.count ?? 0;
  const tab = showPending && searchParams.get("tab") === "pending" ? "pending" : "confirmed";
  const selectTab = (value: string) => {
    const next = new URLSearchParams(searchParams);
    if (value === "confirmed") next.delete("tab");
    else next.set("tab", value);
    setSearchParams(next, { replace: true });
  };

  useEffect(() => {
    if (panel.kind === "edit") {
      panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [panel]);

  // Reset every list filter to its default. Filters set via the controls live only in state, while
  // deep-links (e.g. from analytics) live in the URL and get re-applied by the sync effect above — so
  // clear both to be sure nothing lingers.
  const clearFilters = () => {
    setFilters((f) => ({ size: f.size, page: 0 }));
    const next = new URLSearchParams(searchParams);
    ["from", "to", "direction", "categoryCode", "categoryGroup"].forEach((k) => next.delete(k));
    setSearchParams(next, { replace: true });
  };

  const closePanel = () => setPanel({ kind: "closed" });
  const toggleCreate = () =>
    setPanel((p) => (p.kind === "create" ? { kind: "closed" } : { kind: "create" }));
  const startEdit = (tx: Transaction) => {
    setPanel({ kind: "edit", tx });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{t("tx.title")}</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("tx.description")}</p>
        </div>
        {tab === "confirmed" && (
          <div className="flex gap-2">
            <a
              href={`/api/households/${household.householdId}/transactions/export.csv${buildExportQuery(filters)}`}
              download
              className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
            >
              {t("common.export_csv")}
            </a>
            <Button onClick={toggleCreate}>
              {panel.kind === "create" ? t("common.cancel") : t("tx.quick_add")}
            </Button>
          </div>
        )}
      </div>

      {showPending && (
        <TabBar
          items={[
            { value: "confirmed", label: t("tx.tab_confirmed") },
            { value: "pending", label: `${t("banks.pending_tab")} (${pendingCount})` },
          ]}
          value={tab}
          onChange={selectTab}
          ariaLabel={t("tx.tabs_aria")}
        />
      )}

      {tab === "pending" && (
        <PendingInbox householdId={household.householdId} currency={household.currency} locale={i18n.language} />
      )}

      {tab === "confirmed" && panel.kind !== "closed" && (
        <div ref={panelRef}>
        <Card>
          <CardHeader>
            <p className="font-medium">{panel.kind === "edit" ? t("tx.edit_title") : t("tx.new")}</p>
          </CardHeader>
          <CardBody>
            <QuickAddForm
              householdId={household.householdId}
              initial={panel.kind === "edit" ? panel.tx : undefined}
              onSaved={closePanel}
              onCancel={closePanel}
            />
          </CardBody>
        </Card>
        </div>
      )}

      {tab === "confirmed" && (
      <Card>
        <CardHeader>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
            <div>
              <Label>{t("tx.filter_from")}</Label>
              <Input type="date" value={filters.from ?? ""} onChange={(e) => setFilters({ ...filters, from: e.target.value || undefined, page: 0 })} />
            </div>
            <div>
              <Label>{t("tx.filter_to")}</Label>
              <Input type="date" value={filters.to ?? ""} onChange={(e) => setFilters({ ...filters, to: e.target.value || undefined, page: 0 })} />
            </div>
            <div>
              <Label>{t("common.direction")}</Label>
              <Select
                value={filters.direction ?? ""}
                onChange={(e) =>
                  setFilters({
                    ...filters,
                    direction: (e.target.value || undefined) as "income" | "expense" | undefined,
                    page: 0,
                  })
                }
              >
                <option value="">{t("common.all")}</option>
                <option value="income">{t("common.income")}</option>
                <option value="expense">{t("common.expense")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("common.category")}</Label>
              <Select value={filters.categoryCode ?? ""} onChange={(e) => setFilters({ ...filters, categoryCode: e.target.value || undefined, page: 0 })}>
                <option value="">{t("common.all")}</option>
                {categories
                  .slice()
                  .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t), i18n.language, { sensitivity: "base" }))
                  .map((c) => (
                    <option key={c.code} value={c.code}>
                      {categoryLabel(c, t)} {categoryIcon(c.code)}
                    </option>
                  ))}
              </Select>
            </div>
          </div>
          {hasActiveTransactionFilters(filters) && (
            <div className="mt-3 flex justify-end">
              <Button variant="ghost" onClick={clearFilters}>{t("tx.clear_filters")}</Button>
            </div>
          )}
        </CardHeader>
        <CardBody>
          {isLoading || !page ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : page.items.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 md:hidden">
                {page.items.map((tx) => (
                  <li key={tx.id} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <p className="font-medium">
                          <span className="mr-1.5" aria-hidden>{categoryIcon(tx.categoryCode)}</span>
                          {categoryLabelByCode(tx.categoryCode, categories, t)}
                        </p>
                        <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">
                          {tx.description ?? t("tx.no_description")}
                        </p>
                        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                          {formatDate(tx.occurrenceDate, i18n.language)}
                        </p>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <span className={`font-medium ${tx.direction === "income" ? "text-green-600 dark:text-green-400" : "text-gray-900 dark:text-gray-100"}`}>
                          {tx.direction === "income" ? "+" : "-"}
                          {formatMoney(tx.amount, household.currency, i18n.language)}
                        </span>
                        <div className="flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => startEdit(tx)}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              if (window.confirm(t("tx.delete_confirm"))) void del.mutate(tx.id);
                            }}
                          >
                            <span aria-hidden>🗑️</span>
                          </Button>
                        </div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
              <table className="hidden w-full text-sm md:table">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="py-2">{t("common.date")}</th>
                    <th>{t("common.category")}</th>
                    <th>{t("common.description")}</th>
                    <th className="text-right">{t("common.amount")}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((tx) => (
                    <tr key={tx.id} className="border-t border-border">
                      <td className="py-2">{formatDate(tx.occurrenceDate, i18n.language)}</td>
                      <td>
                        <span className="mr-1.5" aria-hidden>{categoryIcon(tx.categoryCode)}</span>
                        {categoryLabelByCode(tx.categoryCode, categories, t)}
                      </td>
                      <td className="text-gray-600 dark:text-gray-300">{tx.description ?? t("tx.no_description")}</td>
                      <td className={`text-right font-medium ${tx.direction === "income" ? "text-green-600 dark:text-green-400" : "text-gray-900 dark:text-gray-100"}`}>
                        {tx.direction === "income" ? "+" : "-"}
                        {formatMoney(tx.amount, household.currency, i18n.language)}
                      </td>
                      <td className="text-right">
                        <div className="inline-flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => startEdit(tx)}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              if (window.confirm(t("tx.delete_confirm"))) void del.mutate(tx.id);
                            }}
                          >
                            <span aria-hidden>🗑️</span>
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </CardBody>
      </Card>
      )}
    </div>
  );
}
