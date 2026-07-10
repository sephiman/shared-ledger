import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useAddCashAdjustment,
  useCashAdjustments,
  useCashEstimate,
  useCashSettings,
  useDeleteCashAdjustment,
  useUpdateCashAdjustment,
  useUpdateCashSettings,
  type CashSettings,
  type ValueEntry,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, Input, Label } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";

/**
 * Cash as a hybrid: a manual adjustment series (the truth) on the left, and on the right the
 * per-type flow toggles plus the breakdown of the estimate derived between adjustments.
 */
export function CashTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const today = isoToday();
  const money = (v: string) => formatMoney(v, household.currency, i18n.language);

  const { data: entries = [] } = useCashAdjustments(hid);
  const { data: estimate } = useCashEstimate(hid, today);
  const { data: settings } = useCashSettings(hid);
  const add = useAddCashAdjustment(hid);
  const update = useUpdateCashAdjustment(hid);
  const del = useDeleteCashAdjustment(hid);
  const saveSettings = useUpdateCashSettings(hid);

  const [editor, setEditor] = useState<null | { entryId?: string; date: string; value: string }>(null);

  function openAdd() {
    setEditor({ date: today, value: "" });
  }
  function openEdit(e: ValueEntry) {
    setEditor({ entryId: e.id, date: e.date, value: e.value });
  }
  async function save() {
    if (!editor) return;
    if (!editor.date || editor.value.trim() === "" || Number.isNaN(Number(editor.value))) return;
    if (editor.entryId) await update.mutateAsync({ entryId: editor.entryId, date: editor.date, value: editor.value });
    else await add.mutateAsync({ date: editor.date, value: editor.value });
    setEditor(null);
  }

  function toggle(key: keyof CashSettings) {
    if (!settings) return;
    saveSettings.mutate({ ...settings, [key]: !settings[key] });
  }

  const hasEstimate = estimate?.estimate != null;
  const signed = (v: string) => (Number(v) >= 0 ? "+" : "") + money(v);

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
      {/* Left panel — estimated balance + the adjustment series (source of truth). */}
      <Card>
        <CardHeader><p className="font-medium">{t("networth.cash")}</p></CardHeader>
        <CardBody className="space-y-4">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {t("networth.cash_estimated_balance")}
            </p>
            {hasEstimate ? (
              <p className="font-mono text-2xl tabular-nums">{money(estimate!.estimate!)}</p>
            ) : (
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("networth.cash_no_adjustments")}</p>
            )}
          </div>

          <div>
            <div className="mb-1 flex items-center justify-between">
              <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                {t("networth.cash_adjustments")}
              </p>
              {!editor && (
                <Button className="px-2 py-1 text-xs" variant="secondary" onClick={openAdd}>
                  {t("networth.cash_add_adjustment")}
                </Button>
              )}
            </div>

            {editor && (
              <div className="mb-2 space-y-2 rounded border border-border p-2 dark:border-gray-700">
                <div className="flex flex-wrap items-end gap-2">
                  <div>
                    <Label>{t("networth.cash_adjustment_date")}</Label>
                    <Input type="date" value={editor.date} onChange={(e) => setEditor({ ...editor, date: e.target.value })} />
                  </div>
                  <div>
                    <Label>{t("networth.value_amount")}</Label>
                    <Input type="number" step="0.01" value={editor.value} onChange={(e) => setEditor({ ...editor, value: e.target.value })} />
                  </div>
                  <Button className="px-2 py-1 text-xs" onClick={save} disabled={add.isPending || update.isPending}>{t("common.save")}</Button>
                  <Button className="px-2 py-1 text-xs" variant="secondary" onClick={() => setEditor(null)}>{t("common.cancel")}</Button>
                </div>
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.cash_end_of_day_hint")}</p>
              </div>
            )}

            {entries.length === 0 ? (
              <p className="text-xs text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
            ) : (
              <ul className="space-y-1 text-sm">
                {entries.map((e) => (
                  <li key={e.id} className="flex items-center justify-between gap-3 border-t border-border py-1 first:border-t-0 dark:border-gray-700">
                    <span className="text-gray-600 dark:text-gray-300">{formatDate(e.date, i18n.language)}</span>
                    <span className="font-mono tabular-nums">{money(e.value)}</span>
                    <span className="flex gap-1">
                      <Button variant="ghost" className="px-2" title={t("common.edit")} onClick={() => openEdit(e)}><span aria-hidden>✏️</span></Button>
                      <Button variant="ghost" className="px-2" title={t("common.delete")} onClick={() => { if (window.confirm(t("common.delete") + "?")) void del.mutateAsync(e.id); }}><span aria-hidden>🗑️</span></Button>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </CardBody>
      </Card>

      {/* Right panel — what affects the estimate + the breakdown. */}
      <Card>
        <CardHeader><p className="font-medium">{t("networth.cash_what_affects")}</p></CardHeader>
        <CardBody className="space-y-4">
          <div className="space-y-2">
            <Toggle label={t("networth.cash_flow_transactions")} checked={settings?.includeTransactions ?? true} onChange={() => toggle("includeTransactions")} />
            <Toggle label={t("networth.cash_flow_lendings")} checked={settings?.includeLendings ?? true} onChange={() => toggle("includeLendings")} />
            <Toggle label={t("networth.cash_flow_movements")} checked={settings?.includeMovements ?? true} onChange={() => toggle("includeMovements")} />
          </div>

          <div className="border-t border-border pt-3 dark:border-gray-700">
            <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {t("networth.cash_breakdown_title")}
            </p>
            {hasEstimate && estimate?.anchorDate ? (
              <div className="space-y-1 text-sm text-gray-600 dark:text-gray-300">
                <p>
                  {t("networth.cash_breakdown", {
                    date: formatDate(estimate.anchorDate, i18n.language),
                    amount: money(estimate.anchorAmount ?? "0"),
                    net: signed(estimate.netFlows),
                    estimate: money(estimate.estimate!),
                  })}
                </p>
                <ul className="mt-1 space-y-0.5 text-xs text-gray-500 dark:text-gray-400">
                  <li className="flex justify-between gap-3"><span>{t("networth.cash_flow_transactions")}</span><span className="font-mono tabular-nums">{signed(estimate.netTransactions)}</span></li>
                  <li className="flex justify-between gap-3"><span>{t("networth.cash_flow_lendings")}</span><span className="font-mono tabular-nums">{signed(estimate.netLendings)}</span></li>
                  <li className="flex justify-between gap-3"><span>{t("networth.cash_flow_movements")}</span><span className="font-mono tabular-nums">{signed(estimate.netMovements)}</span></li>
                </ul>
              </div>
            ) : (
              <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.cash_no_adjustments")}</p>
            )}
          </div>
        </CardBody>
      </Card>
    </div>
  );
}

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: () => void }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <input type="checkbox" checked={checked} onChange={onChange} />
      <span>{label}</span>
    </label>
  );
}
