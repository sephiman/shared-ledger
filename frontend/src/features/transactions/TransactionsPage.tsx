import { useState } from "react";
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
import { Button, Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/dates";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { QuickAddForm } from "./QuickAddForm";

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
  const [searchParams] = useSearchParams();
  const [filters, setFilters] = useState<TransactionFilters>(() => initialFiltersFromUrl(searchParams));
  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  const { data: page, isLoading } = useTransactions(household.householdId, filters);
  const { data: categories = [] } = useCategories(household.householdId);
  const del = useDeleteTransaction(household.householdId);

  const closePanel = () => setPanel({ kind: "closed" });
  const toggleCreate = () =>
    setPanel((p) => (p.kind === "create" ? { kind: "closed" } : { kind: "create" }));
  const startEdit = (tx: Transaction) => {
    setPanel({ kind: "edit", tx });
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{t("tx.title")}</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("tx.description")}</p>
        </div>
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
      </div>

      {panel.kind !== "closed" && (
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
      )}

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
                      {categoryIcon(c.code)} {categoryLabel(c, t)}
                    </option>
                  ))}
              </Select>
            </div>
          </div>
        </CardHeader>
        <CardBody>
          {isLoading || !page ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : page.items.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
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
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
