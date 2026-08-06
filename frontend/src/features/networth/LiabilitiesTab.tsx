import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useAddLiabilityValue,
  useDeleteLiability,
  useDeleteLiabilityValue,
  useLiabilities,
  useLiabilityValues,
  useUpdateLiabilityValue,
  useUpsertLiability,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { NamedValueCard, type DetailsDraft } from "./NamedValueCard";
import { AmortizationPanel } from "./AmortizationPanel";

type EditingLiability = { id?: string; name: string; active: boolean; amortizable: boolean; chargeDay: string };

export function LiabilitiesTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const { data: items = [] } = useLiabilities(hid);
  const upsert = useUpsertLiability(hid);
  const del = useDeleteLiability(hid);
  // Top form: create a liability, or edit an amortizable one (simple liabilities edit inside their card).
  const [editing, setEditing] = useState<EditingLiability | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [chargeError, setChargeError] = useState<string | null>(null);
  const [openAmort, setOpenAmort] = useState<string | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);

  // The edit form renders at the top of the page while the list stays below, so scroll it into
  // view when editing starts — otherwise clicking edit on a row far down leaves the form off-screen.
  useEffect(() => {
    if (editing) panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [editing]);

  function startEdit(value: EditingLiability | null) {
    setEditing(value);
    setNameError(null);
    setChargeError(null);
  }

  async function saveDetails(id: string, d: DetailsDraft) {
    await upsert.mutateAsync({
      id,
      name: d.name,
      active: d.active,
      amortizable: d.amortizable ?? false,
      chargeDay: d.amortizable && d.chargeDay ? Number(d.chargeDay) : null,
    });
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.liabilities_description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${hid}/liabilities/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border-strong bg-raised px-4 py-2 text-sm font-medium text-gray-900 hover:bg-raised-hover dark:text-gray-100"
          >
            {t("networth.export_liabilities")}
          </a>
          <a
            href={`/api/households/${hid}/liabilities/amortization/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border-strong bg-raised px-4 py-2 text-sm font-medium text-gray-900 hover:bg-raised-hover dark:text-gray-100"
          >
            {t("networth.export_schedules")}
          </a>
          <Button onClick={() => startEdit({ name: "", active: true, amortizable: false, chargeDay: "" })}>{t("networth.new_liability")}</Button>
        </div>
      </div>

      {editing && (
        <div ref={panelRef}>
        <Card>
          <CardHeader><p className="font-medium">{editing.id ? t("common.edit") : t("networth.new_liability")}</p></CardHeader>
          <CardBody className="space-y-3">
            <div>
              <Label>{t("networth.liability_name")}</Label>
              <Input value={editing.name} invalid={!!nameError} onChange={(e) => { setEditing({ ...editing, name: e.target.value }); if (nameError) setNameError(null); }} />
              <FieldError message={nameError} />
            </div>
            <div className="flex items-center gap-2">
              <input id="liab-active" type="checkbox" checked={editing.active} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} />
              <Label htmlFor="liab-active" className="mb-0">{t("networth.liability_active")}</Label>
            </div>
            <div className="flex items-center gap-2">
              <input id="liab-amort" type="checkbox" checked={editing.amortizable} onChange={(e) => setEditing({ ...editing, amortizable: e.target.checked })} />
              <Label htmlFor="liab-amort" className="mb-0">{t("networth.amortizable")}</Label>
            </div>
            {editing.amortizable && (
              <div>
                <Label>{t("networth.charge_day")}</Label>
                <Input type="number" min={1} max={31} invalid={!!chargeError} value={editing.chargeDay} onChange={(e) => { setEditing({ ...editing, chargeDay: e.target.value }); if (chargeError) setChargeError(null); }} />
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("networth.charge_day_hint")}</p>
                <FieldError message={chargeError} />
              </div>
            )}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => startEdit(null)}>{t("common.cancel")}</Button>
              <Button
                onClick={async () => {
                  if (!editing.name.trim()) { setNameError(t("errors.field_required")); return; }
                  const chargeDay = editing.chargeDay ? Number(editing.chargeDay) : null;
                  if (editing.amortizable && (chargeDay == null || chargeDay < 1 || chargeDay > 31)) {
                    setChargeError(t("networth.charge_day_required"));
                    return;
                  }
                  await upsert.mutateAsync({
                    id: editing.id,
                    name: editing.name,
                    active: editing.active,
                    amortizable: editing.amortizable,
                    chargeDay: editing.amortizable ? chargeDay : null,
                  });
                  startEdit(null);
                }}
              >
                {t("common.save")}
              </Button>
            </div>
          </CardBody>
        </Card>
        </div>
      )}

      <Card>
        <CardBody>
          {items.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <ul className="space-y-2">
              {items.map((it) => it.amortizable ? (
                <li key={it.id} className="rounded-md border border-border p-3">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1 break-words">
                      <p className="font-medium">
                        {it.name}
                        <span className="ml-2 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] uppercase tracking-wide text-primary">{t("networth.amortizable_badge")}</span>
                        {!it.active && <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] uppercase tracking-wide text-gray-500 dark:bg-surface dark:text-gray-400">{t("common.inactive")}</span>}
                      </p>
                      {it.computedBalance != null ? (
                        <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                          <span className="font-mono tabular-nums">{formatMoney(it.computedBalance, household.currency, i18n.language)}</span>
                          {it.computedInstalment != null && (
                            <> · {formatMoney(it.computedInstalment, household.currency, i18n.language)}/{t("networth.per_month")}</>
                          )}
                        </p>
                      ) : (
                        <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{t("networth.amortizable_balance_hint")}</p>
                      )}
                    </div>
                    <div className="flex gap-1">
                      <Button variant="ghost" className="px-2" title={t("networth.amortization")} onClick={() => setOpenAmort(openAmort === it.id ? null : it.id)}><span aria-hidden>📅</span></Button>
                      <Button variant="ghost" className="px-2" title={t("common.edit")} onClick={() => startEdit({ id: it.id, name: it.name, active: it.active, amortizable: it.amortizable, chargeDay: it.chargeDay?.toString() ?? "" })}><span aria-hidden>✏️</span></Button>
                      <Button variant="ghost" className="px-2" title={t("common.delete")} onClick={() => { if (window.confirm(t("common.delete") + "?")) void del.mutate(it.id); }}><span aria-hidden>🗑️</span></Button>
                    </div>
                  </div>
                  {openAmort === it.id && <AmortizationPanel liability={it} onEditorOpen={() => startEdit(null)} />}
                </li>
              ) : (
                <NamedValueCard
                  key={it.id}
                  kind="liability"
                  item={it}
                  useValues={useLiabilityValues}
                  useAdd={useAddLiabilityValue}
                  useUpdate={useUpdateLiabilityValue}
                  useDelete={useDeleteLiabilityValue}
                  onSaveDetails={(d) => saveDetails(it.id, d)}
                  onDelete={() => del.mutate(it.id)}
                />
              ))}
            </ul>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
