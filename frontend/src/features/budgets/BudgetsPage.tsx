import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useBudgets, useMonthSummary, useUpsertBudgets, type BudgetUpsertItem } from "@/api/budgets";
import { useCategories } from "@/api/catalog";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { monthName } from "@/lib/dates";

export function BudgetsPage() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const { data: monthly = [] } = useBudgets(household.householdId, year, month);
  const { data: summary } = useMonthSummary(household.householdId, year, month);
  const { data: annual = [] } = useBudgets(household.householdId, year);
  const { data: categories = [] } = useCategories();
  const upsert = useUpsertBudgets(household.householdId);

  const expenseCategories = useMemo(() => categories.filter((c) => c.kind === "expense"), [categories]);
  const monthlyByCat = useMemo(() => new Map(monthly.map((b) => [b.categoryCode, b])), [monthly]);
  const annualByCat = useMemo(() => new Map(annual.filter((b) => b.month === null).map((b) => [b.categoryCode, b])), [annual]);

  const [editing, setEditing] = useState<{ code: string; mode: "monthly" | "annual"; amount: string } | null>(null);
  const [amountError, setAmountError] = useState<string | null>(null);

  function validateAmount(value: string): string | null {
    if (!value.trim()) return t("errors.field_required");
    const n = Number(value);
    if (!Number.isFinite(n)) return t("errors.number_required");
    if (n < 0) return t("errors.amount_positive");
    return null;
  }

  async function saveOne() {
    if (!editing) return;
    const err = validateAmount(editing.amount);
    if (err) {
      setAmountError(err);
      return;
    }
    const item: BudgetUpsertItem = {
      year,
      month: editing.mode === "monthly" ? month : null,
      categoryCode: editing.code,
      amount: editing.amount,
    };
    await upsert.mutateAsync([item]);
    setEditing(null);
    setAmountError(null);
  }

  function startEdit(code: string, mode: "monthly" | "annual", amount: string) {
    setEditing({ code, mode, amount });
    setAmountError(null);
  }

  function cancelEdit() {
    setEditing(null);
    setAmountError(null);
  }

  function color(percent: number): string {
    if (percent >= 100) return "text-red-600";
    if (percent >= 80) return "text-amber-600";
    return "text-green-600";
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{t("budget.title")}</h1>
          <p className="mt-1 text-sm text-gray-500">{t("budget.description")}</p>
        </div>
        <div className="flex gap-2">
          <div>
            <Label className="text-xs">{t("common.year")}</Label>
            <Select value={year} onChange={(e) => setYear(Number(e.target.value))}>
              {Array.from({ length: 7 }).map((_, i) => {
                const y = now.getFullYear() - 3 + i;
                return <option key={y} value={y}>{y}</option>;
              })}
            </Select>
          </div>
          <div>
            <Label className="text-xs">{t("common.month")}</Label>
            <Select value={month} onChange={(e) => setMonth(Number(e.target.value))}>
              {Array.from({ length: 12 }).map((_, i) => (
                <option key={i + 1} value={i + 1}>{monthName(i + 1, i18n.language)}</option>
              ))}
            </Select>
          </div>
        </div>
      </div>

      <Card>
        <CardHeader>
          <p className="font-medium">{t("budget.monthly")} · {monthName(month, i18n.language)} {year}{summary ? ` — ${summary.daysElapsed}/${summary.daysInMonth}` : ""}</p>
          <p className="mt-1 text-sm text-gray-500">{t("budget.monthly_description")}</p>
        </CardHeader>
        <CardBody>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">{t("common.category")}</th>
                  <th className="text-right">{t("budget.budget")}</th>
                  <th className="text-right">{t("budget.spent")}</th>
                  <th className="text-right">{t("budget.projection")}</th>
                  <th className="text-right">{t("budget.percent")}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {expenseCategories.map((c) => {
                  const row = summary?.rows.find((r) => r.categoryCode === c.code);
                  const isEditingHere = editing?.code === c.code && editing.mode === "monthly";
                  const monthlyAmount = monthlyByCat.get(c.code)?.amount;
                  return (
                    <tr key={c.code} className="border-t border-border">
                      <td className="py-2">{t(`category.${c.code}`)}</td>
                      <td className="text-right">
                        {isEditingHere ? (
                          <div className="flex flex-col items-end gap-1">
                            <div className="flex items-center justify-end gap-2">
                              <Input
                                className="w-24 text-right"
                                type="number"
                                step="0.01"
                                min="0"
                                value={editing!.amount}
                                invalid={!!amountError}
                                onChange={(e) => { setEditing({ ...editing!, amount: e.target.value }); if (amountError) setAmountError(null); }}
                              />
                              <Button onClick={saveOne}>{t("common.save")}</Button>
                              <Button variant="ghost" onClick={cancelEdit}>{t("common.cancel")}</Button>
                            </div>
                            <FieldError message={amountError} />
                          </div>
                        ) : (
                          <span>{monthlyAmount ? formatMoney(monthlyAmount, household.currency, i18n.language) : "—"}</span>
                        )}
                      </td>
                      <td className="text-right">{row ? formatMoney(row.spent, household.currency, i18n.language) : "—"}</td>
                      <td className="text-right">{row ? formatMoney(row.projection, household.currency, i18n.language) : "—"}</td>
                      <td className={`text-right font-medium ${row ? color(row.percent) : ""}`}>{row ? row.percent.toFixed(0) + "%" : "—"}</td>
                      <td className="text-right">
                        {!isEditingHere && (
                          <Button variant="ghost" onClick={() => startEdit(c.code, "monthly", monthlyAmount ?? "")}>
                            {t("common.edit")}
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <p className="font-medium">{t("budget.annual_matrix")} · {year}</p>
          <p className="mt-1 text-sm text-gray-500">{t("budget.annual_description")}</p>
        </CardHeader>
        <CardBody>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">{t("common.category")}</th>
                  <th className="text-right">{t("budget.annual")}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {expenseCategories.map((c) => {
                  const isEditingHere = editing?.code === c.code && editing.mode === "annual";
                  const annualAmount = annualByCat.get(c.code)?.amount;
                  return (
                    <tr key={c.code} className="border-t border-border">
                      <td className="py-2">{t(`category.${c.code}`)}</td>
                      <td className="text-right">
                        {isEditingHere ? (
                          <div className="flex flex-col items-end gap-1">
                            <div className="flex items-center justify-end gap-2">
                              <Input
                                className="w-24 text-right"
                                type="number"
                                step="0.01"
                                min="0"
                                value={editing!.amount}
                                invalid={!!amountError}
                                onChange={(e) => { setEditing({ ...editing!, amount: e.target.value }); if (amountError) setAmountError(null); }}
                              />
                              <Button onClick={saveOne}>{t("common.save")}</Button>
                              <Button variant="ghost" onClick={cancelEdit}>{t("common.cancel")}</Button>
                            </div>
                            <FieldError message={amountError} />
                          </div>
                        ) : (
                          <span>{annualAmount ? formatMoney(annualAmount, household.currency, i18n.language) : "—"}</span>
                        )}
                      </td>
                      <td className="text-right">
                        {!isEditingHere && (
                          <Button variant="ghost" onClick={() => startEdit(c.code, "annual", annualAmount ?? "")}>
                            {t("common.edit")}
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
