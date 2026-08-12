import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useDeleteTransaction,
  useTransactions,
  type Transaction,
  type TransactionFilters,
} from "@/api/transactions";
import { useCategories } from "@/api/catalog";
import { useBankConfig, useBankConnections, usePendingCount } from "@/api/banks";
import { Badge, Button, Card, CardBody, CardHeader, Chip, Input, Label, Select } from "@/components/ui/primitives";
import { TabBar } from "@/components/ui/TabBar";
import { formatMoney } from "@/lib/money";
import { formatDate, formatDayMonthYear } from "@/lib/dates";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { QuickAddForm } from "./QuickAddForm";
import { RegisterRefundDialog } from "./RegisterRefundDialog";
import { displaySign, isPositiveDisplay, signedTxAmount } from "./txDisplay";
import { hasActiveTransactionFilters } from "./txFilters";
import { PendingInbox } from "@/features/banks/PendingInbox";

type PanelMode = { kind: "closed" } | { kind: "create" } | { kind: "edit"; tx: Transaction };

/** Only an ordinary expense can be refunded — refunding a refund is not a thing. */
function canRefund(tx: Transaction): boolean {
  return tx.direction === "expense" && !tx.isRefund;
}

/** The line under a row that says what a refund nets, or how much of a purchase has come back. */
function RefundNote({ tx, currency, locale }: { tx: Transaction; currency: string; locale: string }) {
  const { t } = useTranslation();
  if (tx.isRefund) {
    return (
      <p className="mt-1 flex flex-wrap items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
        <Badge tone="green">{t("tx.refund_badge")}</Badge>
        {tx.refundOf ? (
          <Link
            to={`/transactions?from=${tx.refundOf.occurrenceDate}&to=${tx.refundOf.occurrenceDate}&categoryCode=${tx.refundOf.categoryCode}`}
            className="underline"
          >
            {t("tx.refund_of", {
              description: tx.refundOf.description ?? t("tx.no_description"),
              date: formatDayMonthYear(tx.refundOf.occurrenceDate, locale),
            })}
          </Link>
        ) : (
          tx.refundOfTransactionId && <span>{t("tx.refund_original_deleted")}</span>
        )}
      </p>
    );
  }
  if (!tx.refundedTotal) return null;
  return (
    <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
      {t("tx.refunded_total", { amount: formatMoney(tx.refundedTotal, currency, locale) })}
    </p>
  );
}

function buildExportQuery(filters: TransactionFilters): string {
  const keys: (keyof TransactionFilters)[] = ["from", "to", "direction", "categoryCode", "categoryGroup", "isRefund"];
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
    isRefund: searchParams.get("isRefund") === "true" ? true : undefined,
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
  const urlIsRefund = searchParams.get("isRefund") ?? undefined;
  useEffect(() => {
    setFilters((f) => ({
      ...f,
      page: 0,
      from: urlFrom,
      to: urlTo,
      direction: urlDirection === "income" || urlDirection === "expense" ? urlDirection : undefined,
      categoryCode: urlCategoryCode,
      categoryGroup: urlCategoryGroup,
      isRefund: urlIsRefund === "true" ? true : undefined,
    }));
  }, [urlFrom, urlTo, urlDirection, urlCategoryCode, urlCategoryGroup, urlIsRefund]);
  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  // The purchase a manually-recorded refund is being registered against.
  const [refundFor, setRefundFor] = useState<Transaction | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const { data: page, isLoading } = useTransactions(household.householdId, filters);
  const { data: categories = [] } = useCategories(household.householdId);
  const del = useDeleteTransaction(household.householdId);

  // The "Pending" inbox only appears with credentials + at least one bank linked; without
  // credentials nothing can be ingested to review.
  const { data: bankConfig } = useBankConfig(household.householdId);
  const { data: connections = [] } = useBankConnections(
    household.householdId,
    bankConfig?.credentialsConfigured ?? false,
  );
  const showPending = (bankConfig?.credentialsConfigured ?? false) && connections.length > 0;
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
    ["from", "to", "direction", "categoryCode", "categoryGroup", "isRefund"].forEach((k) => next.delete(k));
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
              className="inline-flex items-center justify-center rounded-md border border-border-strong bg-raised px-4 py-2 text-sm font-medium text-gray-900 hover:bg-raised-hover dark:text-gray-100"
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
            {/* Keyed on the row so switching between edits re-seeds the form's fields. */}
            <QuickAddForm
              key={panel.kind === "edit" ? panel.tx.id : "create"}
              householdId={household.householdId}
              currency={household.currency}
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
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Chip
              active={filters.isRefund === true}
              onClick={() => setFilters({ ...filters, isRefund: filters.isRefund ? undefined : true, page: 0 })}
            >
              {t("tx.refunds_only")}
            </Chip>
            {hasActiveTransactionFilters(filters) && (
              <Button variant="ghost" className="ml-auto" onClick={clearFilters}>{t("tx.clear_filters")}</Button>
            )}
          </div>
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
                  <li key={tx.id} className="rounded-md border border-border p-3">
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
                        <RefundNote tx={tx} currency={household.currency} locale={i18n.language} />
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <span className={`font-medium ${isPositiveDisplay(tx) ? "text-green-600 dark:text-green-400" : "text-gray-900 dark:text-gray-100"}`}>
                          {displaySign(tx)}
                          {formatMoney(signedTxAmount(tx).toFixed(2), household.currency, i18n.language)}
                        </span>
                        <div className="flex gap-1">
                          {canRefund(tx) && (
                            <Button
                              variant="ghost"
                              className="px-2"
                              aria-label={t("tx.register_refund")}
                              title={t("tx.register_refund")}
                              onClick={() => setRefundFor(tx)}
                            >
                              <span aria-hidden>↩️</span>
                            </Button>
                          )}
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
                      <td className="text-gray-600 dark:text-gray-300">
                        {tx.description ?? t("tx.no_description")}
                        <RefundNote tx={tx} currency={household.currency} locale={i18n.language} />
                      </td>
                      <td className={`text-right font-medium ${isPositiveDisplay(tx) ? "text-green-600 dark:text-green-400" : "text-gray-900 dark:text-gray-100"}`}>
                        {displaySign(tx)}
                        {formatMoney(signedTxAmount(tx).toFixed(2), household.currency, i18n.language)}
                      </td>
                      <td className="text-right">
                        <div className="inline-flex gap-1">
                          {canRefund(tx) && (
                            <Button
                              variant="ghost"
                              className="px-2"
                              aria-label={t("tx.register_refund")}
                              title={t("tx.register_refund")}
                              onClick={() => setRefundFor(tx)}
                            >
                              <span aria-hidden>↩️</span>
                            </Button>
                          )}
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

      {refundFor && (
        <RegisterRefundDialog
          open
          householdId={household.householdId}
          original={refundFor}
          currency={household.currency}
          locale={i18n.language}
          onClose={() => setRefundFor(null)}
        />
      )}
    </div>
  );
}
