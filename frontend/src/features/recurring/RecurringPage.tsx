import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useCategories } from "@/api/catalog";
import {
  useCreateRecurring,
  useDeleteRecurring,
  useMaterializeRecurring,
  useRecurringTemplates,
  useUpdateRecurring,
  type RecurringInput,
  type RecurringTemplate,
} from "@/api/recurring";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";
import { formatDate, isoToday, monthName, weekdayName } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";

function defaultInput(): RecurringInput {
  // Defaults match today's date so a freshly-created template fires today
  // when "Materialize now" is clicked. The user can change any of these.
  const today = new Date();
  const isoMon = today.getDay() === 0 ? 7 : today.getDay(); // 1=Mon..7=Sun (JS Sunday=0)
  return {
    direction: "expense",
    categoryCode: "",
    amount: "",
    description: null,
    cadence: "monthly",
    dayOfMonth: today.getDate(),
    dayOfWeek: isoMon,
    monthOfYear: today.getMonth() + 1,
    dayOfMonthYearly: today.getDate(),
    startDate: isoToday(),
    endDate: null,
    active: true,
  };
}

export function RecurringPage() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: templates = [], isLoading } = useRecurringTemplates(household.householdId);
  const { data: categories = [] } = useCategories(household.householdId);
  const create = useCreateRecurring(household.householdId);
  const update = useUpdateRecurring(household.householdId);
  const del = useDeleteRecurring(household.householdId);
  const materialize = useMaterializeRecurring(household.householdId);
  const [editing, setEditing] = useState<RecurringTemplate | null>(null);
  const [draft, setDraft] = useState<RecurringInput | null>(null);
  const [errors, setErrors] = useState<{ categoryCode?: string; amount?: string; startDate?: string }>({});

  function startNew() {
    setEditing(null);
    setDraft(defaultInput());
    setErrors({});
  }

  function startEdit(tpl: RecurringTemplate) {
    setEditing(tpl);
    setDraft({
      direction: tpl.direction,
      categoryCode: tpl.categoryCode,
      amount: tpl.amount,
      description: tpl.description,
      cadence: tpl.cadence,
      dayOfMonth: tpl.dayOfMonth,
      dayOfWeek: tpl.dayOfWeek,
      monthOfYear: tpl.monthOfYear,
      dayOfMonthYearly: tpl.dayOfMonthYearly,
      startDate: tpl.startDate,
      endDate: tpl.endDate,
      active: tpl.active,
    });
    setErrors({});
  }

  function cancelDraft() {
    setDraft(null);
    setEditing(null);
    setErrors({});
  }

  async function save() {
    if (!draft) return;
    const next: typeof errors = {};
    if (!draft.categoryCode) next.categoryCode = t("errors.select_required");
    const amount = Number(draft.amount);
    if (!draft.amount.trim()) next.amount = t("errors.field_required");
    else if (!Number.isFinite(amount) || amount <= 0) next.amount = t("errors.amount_positive");
    if (!draft.startDate) next.startDate = t("errors.field_required");
    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }
    const normalized: RecurringInput = {
      ...draft,
      dayOfMonth: draft.cadence === "monthly" ? draft.dayOfMonth ?? 1 : null,
      dayOfWeek: draft.cadence === "weekly" ? draft.dayOfWeek ?? 1 : null,
      monthOfYear: draft.cadence === "yearly" ? draft.monthOfYear ?? 1 : null,
      dayOfMonthYearly: draft.cadence === "yearly" ? draft.dayOfMonthYearly ?? 1 : null,
    };
    if (editing) {
      await update.mutateAsync({ id: editing.id, input: normalized });
    } else {
      await create.mutateAsync(normalized);
    }
    cancelDraft();
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{t("recurring.title")}</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("recurring.description")}</p>
        </div>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/recurring-templates/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={startNew}>{t("recurring.new")}</Button>
        </div>
      </div>

      {draft && (
        <Card>
          <CardHeader>
            <p className="font-medium">{editing ? t("common.edit") : t("recurring.new")}</p>
          </CardHeader>
          <CardBody>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <Label>{t("common.direction")}</Label>
                <Select
                  value={draft.direction}
                  onChange={(e) => setDraft({ ...draft, direction: e.target.value as "income" | "expense", categoryCode: "" })}
                >
                  <option value="expense">{t("common.expense")}</option>
                  <option value="income">{t("common.income")}</option>
                </Select>
              </div>
              <div>
                <Label>{t("common.category")}</Label>
                <Select
                  value={draft.categoryCode}
                  invalid={!!errors.categoryCode}
                  onChange={(e) => { setDraft({ ...draft, categoryCode: e.target.value }); if (errors.categoryCode) setErrors({ ...errors, categoryCode: undefined }); }}
                >
                  <option value="">—</option>
                  {categories
                    .filter((c) => c.kind === draft.direction)
                    .slice()
                    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t), i18n.language, { sensitivity: "base" }))
                    .map((c) => (
                      <option key={c.code} value={c.code}>
                        {categoryLabel(c, t)} {categoryIcon(c.code)}
                      </option>
                    ))}
                </Select>
                <FieldError message={errors.categoryCode} />
              </div>
              <div>
                <Label>{t("common.amount")}</Label>
                <Input
                  type="number"
                  step="0.01"
                  min="0.01"
                  value={draft.amount}
                  invalid={!!errors.amount}
                  onChange={(e) => { setDraft({ ...draft, amount: e.target.value }); if (errors.amount) setErrors({ ...errors, amount: undefined }); }}
                />
                <FieldError message={errors.amount} />
              </div>
              <div>
                <Label>{t("recurring.cadence")}</Label>
                <Select value={draft.cadence} onChange={(e) => setDraft({ ...draft, cadence: e.target.value as RecurringInput["cadence"] })}>
                  <option value="weekly">{t("recurring.weekly")}</option>
                  <option value="monthly">{t("recurring.monthly")}</option>
                  <option value="yearly">{t("recurring.yearly")}</option>
                </Select>
              </div>
              {draft.cadence === "monthly" && (
                <div>
                  <Label>{t("recurring.day_of_month")}</Label>
                  <Input type="number" min={1} max={31} value={draft.dayOfMonth ?? ""} onChange={(e) => setDraft({ ...draft, dayOfMonth: Number(e.target.value) })} />
                </div>
              )}
              {draft.cadence === "weekly" && (
                <div>
                  <Label>{t("recurring.day_of_week")}</Label>
                  <Select value={draft.dayOfWeek ?? 1} onChange={(e) => setDraft({ ...draft, dayOfWeek: Number(e.target.value) })}>
                    {[1, 2, 3, 4, 5, 6, 7].map((d) => (
                      <option key={d} value={d}>{weekdayName(d, i18n.language)}</option>
                    ))}
                  </Select>
                </div>
              )}
              {draft.cadence === "yearly" && (
                <>
                  <div>
                    <Label>{t("recurring.month_of_year")}</Label>
                    <Select value={draft.monthOfYear ?? 1} onChange={(e) => setDraft({ ...draft, monthOfYear: Number(e.target.value) })}>
                      {Array.from({ length: 12 }).map((_, i) => (
                        <option key={i + 1} value={i + 1}>{monthName(i + 1, i18n.language)}</option>
                      ))}
                    </Select>
                  </div>
                  <div>
                    <Label>{t("recurring.day_of_month")}</Label>
                    <Input type="number" min={1} max={31} value={draft.dayOfMonthYearly ?? ""} onChange={(e) => setDraft({ ...draft, dayOfMonthYearly: Number(e.target.value) })} />
                  </div>
                </>
              )}
              <div>
                <Label>{t("recurring.start")}</Label>
                <Input
                  type="date"
                  value={draft.startDate}
                  invalid={!!errors.startDate}
                  onChange={(e) => { setDraft({ ...draft, startDate: e.target.value }); if (errors.startDate) setErrors({ ...errors, startDate: undefined }); }}
                />
                <FieldError message={errors.startDate} />
              </div>
              <div>
                <Label>{t("recurring.end")}</Label>
                <Input type="date" value={draft.endDate ?? ""} onChange={(e) => setDraft({ ...draft, endDate: e.target.value || null })} />
              </div>
              <div className="md:col-span-2">
                <Label>{t("common.description")}</Label>
                <Textarea value={draft.description ?? ""} onChange={(e) => setDraft({ ...draft, description: e.target.value })} rows={2} />
              </div>
              <div className="flex items-center gap-2">
                <input id="active" type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
                <Label htmlFor="active" className="mb-0">{t("common.active")}</Label>
              </div>
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <Button variant="secondary" onClick={cancelDraft}>{t("common.cancel")}</Button>
              <Button onClick={save}>{t("common.save")}</Button>
            </div>
          </CardBody>
        </Card>
      )}

      <Card>
        <CardBody>
          {isLoading ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : templates.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 md:hidden">
                {templates.map((tpl) => (
                  <li key={tpl.id} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <p className="font-medium">
                          <span className="mr-1.5" aria-hidden>{categoryIcon(tpl.categoryCode)}</span>
                          {categoryLabelByCode(tpl.categoryCode, categories, t)}
                        </p>
                        {tpl.description && (
                          <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">{tpl.description}</p>
                        )}
                        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                          {t(`recurring.${tpl.cadence}`)} · {t("recurring.next")}: {tpl.nextFireDate ? formatDate(tpl.nextFireDate, i18n.language) : "—"}
                          {!tpl.active && <> · {t("common.no")}</>}
                        </p>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <span className="font-medium">{formatMoney(tpl.amount, household.currency, i18n.language)}</span>
                        <div className="flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("recurring.run_now")}
                            title={t("recurring.run_now")}
                            onClick={() => materialize.mutate(tpl.id)}
                          >
                            <span aria-hidden>▶️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => startEdit(tpl)}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              if (window.confirm(t("common.delete") + "?")) void del.mutate(tpl.id);
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
                    <th className="py-2">{t("common.category")}</th>
                    <th>{t("common.description")}</th>
                    <th>{t("recurring.cadence")}</th>
                    <th className="text-right">{t("common.amount")}</th>
                    <th>{t("recurring.next")}</th>
                    <th>{t("recurring.last")}</th>
                    <th>{t("common.active")}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {templates.map((tpl) => (
                    <tr key={tpl.id} className="border-t border-border">
                      <td className="py-2">
                        <span className="mr-1.5" aria-hidden>{categoryIcon(tpl.categoryCode)}</span>
                        {categoryLabelByCode(tpl.categoryCode, categories, t)}
                      </td>
                      <td className="text-gray-600 dark:text-gray-300">{tpl.description ?? "—"}</td>
                      <td>{t(`recurring.${tpl.cadence}`)}</td>
                      <td className="text-right">{formatMoney(tpl.amount, household.currency, i18n.language)}</td>
                      <td>{tpl.nextFireDate ? formatDate(tpl.nextFireDate, i18n.language) : "—"}</td>
                      <td>{tpl.lastMaterializedThrough ? formatDate(tpl.lastMaterializedThrough, i18n.language) : "—"}</td>
                      <td>{tpl.active ? t("common.yes") : t("common.no")}</td>
                      <td className="text-right">
                        <div className="inline-flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("recurring.run_now")}
                            title={t("recurring.run_now")}
                            onClick={() => materialize.mutate(tpl.id)}
                          >
                            <span aria-hidden>▶️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => startEdit(tpl)}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              if (window.confirm(t("common.delete") + "?")) void del.mutate(tpl.id);
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
    </div>
  );
}
