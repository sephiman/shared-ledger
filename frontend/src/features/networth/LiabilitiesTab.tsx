import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { apiErrorMessage, asApiError } from "@/api/client";
import {
  useAddLiabilityValue,
  useDeleteLiability,
  useDeleteLiabilityValue,
  useLiabilities,
  useLiabilityValues,
  useUpdateLiabilityValue,
  useUpsertLiability,
  type LiabilityType,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { isoToday } from "@/lib/dates";
import { NamedValueCard, type DetailsDraft } from "./NamedValueCard";
import { AmortizationPanel } from "./AmortizationPanel";

const LIABILITY_TYPES: LiabilityType[] = ["other_debt", "mortgage"];

type EditingLiability = {
  id?: string;
  name: string;
  type: LiabilityType;
  active: boolean;
  countsTowardNetWorth: boolean;
  amortizable: boolean;
  chargeDay: string;
  initialBalance: string;
  initialBalanceDate: string;
};

type FormErrors = {
  name?: string;
  chargeDay?: string;
  initialBalance?: string;
  initialBalanceDate?: string;
  submit?: string;
};

export function LiabilitiesTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const { data: items = [] } = useLiabilities(hid);
  const upsert = useUpsertLiability(hid);
  const del = useDeleteLiability(hid);
  // Top form: create a liability, or edit an amortizable one (simple liabilities edit inside their card).
  const [editing, setEditing] = useState<EditingLiability | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [openAmort, setOpenAmort] = useState<string | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);

  // The edit form renders at the top of the page while the list stays below, so scroll it into
  // view when editing starts — otherwise clicking edit on a row far down leaves the form off-screen.
  useEffect(() => {
    if (editing) panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [editing]);

  function startEdit(value: EditingLiability | null) {
    setEditing(value);
    setErrors({});
  }

  function clearError(field: keyof FormErrors) {
    setErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
  }

  async function saveDetails(id: string, d: DetailsDraft) {
    await upsert.mutateAsync({
      id,
      name: d.name,
      type: d.liabilityType ?? "other_debt",
      active: d.active,
      countsTowardNetWorth: d.countsTowardNetWorth ?? true,
      amortizable: d.amortizable ?? false,
      chargeDay: d.amortizable && d.chargeDay ? Number(d.chargeDay) : null,
    });
  }

  async function submitTopForm() {
    if (!editing) return;
    const next: FormErrors = {};
    if (!editing.name.trim()) next.name = t("errors.field_required");

    const chargeDay = editing.chargeDay ? Number(editing.chargeDay) : null;
    if (editing.amortizable && (chargeDay == null || chargeDay < 1 || chargeDay > 31)) {
      next.chargeDay = t("networth.charge_day_required");
    }

    let normalizedBalance: string | null = null;
    let balanceDate: string | null = null;
    if (!editing.id && !editing.amortizable) {
      const raw = editing.initialBalance.trim().replace(",", ".");
      const num = Number(raw);
      if (!raw) {
        next.initialBalance = t("errors.field_required");
      } else if (!Number.isFinite(num) || num <= 0) {
        next.initialBalance = t("networth.initial_balance_positive");
      } else {
        normalizedBalance = raw;
      }
      if (!editing.initialBalanceDate) {
        next.initialBalanceDate = t("errors.field_required");
      } else if (editing.initialBalanceDate > isoToday()) {
        next.initialBalanceDate = t("networth.date_not_future");
      } else {
        balanceDate = editing.initialBalanceDate;
      }
    }

    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }

    try {
      await upsert.mutateAsync({
        id: editing.id,
        name: editing.name,
        type: editing.type,
        active: editing.active,
        countsTowardNetWorth: editing.countsTowardNetWorth,
        amortizable: editing.amortizable,
        chargeDay: editing.amortizable ? chargeDay : null,
        initialBalance: editing.id || editing.amortizable ? null : normalizedBalance,
        initialBalanceDate: editing.id || editing.amortizable ? null : balanceDate,
      });
      startEdit(null);
    } catch (err) {
      const api = asApiError(err);
      if (api.fields && Object.keys(api.fields).length > 0) {
        setErrors({
          name: api.fields.name,
          chargeDay: api.fields.chargeDay,
          initialBalance: api.fields.initialBalance,
          initialBalanceDate: api.fields.initialBalanceDate,
        });
      } else {
        setErrors({ submit: apiErrorMessage(err, t) });
      }
    }
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
          <Button
            onClick={() =>
              startEdit({
                name: "",
                type: "other_debt",
                active: true,
                countsTowardNetWorth: true,
                amortizable: false,
                chargeDay: "",
                initialBalance: "",
                initialBalanceDate: isoToday(),
              })
            }
          >
            {t("networth.new_liability")}
          </Button>
        </div>
      </div>

      {editing && (
        <div ref={panelRef}>
          <Card>
            <CardHeader><p className="font-medium">{editing.id ? t("common.edit") : t("networth.new_liability")}</p></CardHeader>
            <CardBody className="space-y-3">
              <div>
                <Label htmlFor="liab-name">{t("networth.liability_name")}</Label>
                <Input
                  id="liab-name"
                  value={editing.name}
                  invalid={!!errors.name}
                  onChange={(e) => { setEditing({ ...editing, name: e.target.value }); clearError("name"); }}
                />
                <FieldError message={errors.name} />
              </div>

              <div>
                <Label htmlFor="liab-type">{t("networth.liability_type")}</Label>
                <Select
                  id="liab-type"
                  value={editing.type}
                  onChange={(e) => setEditing({ ...editing, type: e.target.value as LiabilityType })}
                >
                  {LIABILITY_TYPES.map((tp) => (
                    <option key={tp} value={tp}>{t(`networth.liability_type_${tp}`)}</option>
                  ))}
                </Select>
              </div>

              <div>
                <div className="flex items-center gap-2">
                  <input
                    id="liab-amort"
                    type="checkbox"
                    checked={editing.amortizable}
                    onChange={(e) => {
                      setEditing({ ...editing, amortizable: e.target.checked });
                      clearError("chargeDay");
                      clearError("initialBalance");
                      clearError("initialBalanceDate");
                    }}
                  />
                  <Label htmlFor="liab-amort" className="mb-0">{t("networth.amortizable")}</Label>
                </div>
                <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{t("networth.amortizable_hint")}</p>
              </div>

              {editing.amortizable && (
                <div>
                  <Label htmlFor="liab-charge-day">{t("networth.charge_day")}</Label>
                  <Input
                    id="liab-charge-day"
                    type="number"
                    min={1}
                    max={31}
                    invalid={!!errors.chargeDay}
                    value={editing.chargeDay}
                    onChange={(e) => { setEditing({ ...editing, chargeDay: e.target.value }); clearError("chargeDay"); }}
                  />
                  <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("networth.charge_day_hint")}</p>
                  <FieldError message={errors.chargeDay} />
                </div>
              )}

              {!editing.id && (
                editing.amortizable ? (
                  <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.amortizable_initial_balance_note")}</p>
                ) : (
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div>
                      <Label htmlFor="liab-initial-balance">{t("networth.initial_balance")}</Label>
                      <Input
                        id="liab-initial-balance"
                        inputMode="decimal"
                        placeholder="0,00"
                        value={editing.initialBalance}
                        invalid={!!errors.initialBalance}
                        onChange={(e) => { setEditing({ ...editing, initialBalance: e.target.value }); clearError("initialBalance"); }}
                      />
                      <FieldError message={errors.initialBalance} />
                    </div>
                    <div>
                      <Label htmlFor="liab-initial-balance-date">{t("networth.initial_balance_date")}</Label>
                      <Input
                        id="liab-initial-balance-date"
                        type="date"
                        max={isoToday()}
                        value={editing.initialBalanceDate}
                        invalid={!!errors.initialBalanceDate}
                        onChange={(e) => { setEditing({ ...editing, initialBalanceDate: e.target.value }); clearError("initialBalanceDate"); }}
                      />
                      <FieldError message={errors.initialBalanceDate} />
                    </div>
                  </div>
                )
              )}

              <div>
                <div className="flex items-center gap-2">
                  <input
                    id="liab-counts"
                    type="checkbox"
                    checked={editing.countsTowardNetWorth}
                    onChange={(e) => setEditing({ ...editing, countsTowardNetWorth: e.target.checked })}
                  />
                  <Label htmlFor="liab-counts" className="mb-0">{t("networth.counts_toward_net_worth")}</Label>
                </div>
                <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{t("networth.counts_toward_net_worth_hint")}</p>
              </div>

              <div>
                <div className="flex items-center gap-2">
                  <input
                    id="liab-active"
                    type="checkbox"
                    checked={editing.active}
                    onChange={(e) => setEditing({ ...editing, active: e.target.checked })}
                  />
                  <Label htmlFor="liab-active" className="mb-0">{t("networth.liability_active")}</Label>
                </div>
                <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{t("networth.liability_active_hint")}</p>
              </div>

              <FieldError message={errors.submit} />

              <div className="flex justify-end gap-2">
                <Button variant="secondary" onClick={() => startEdit(null)}>{t("common.cancel")}</Button>
                <Button onClick={submitTopForm} disabled={upsert.isPending}>
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
                        <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] uppercase tracking-wide text-gray-600 dark:bg-chip dark:text-gray-300">
                          {t(`networth.liability_type_${it.type}`)}
                        </span>
                        <span className="ml-2 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] uppercase tracking-wide text-primary">{t("networth.amortizable_badge")}</span>
                        {!it.countsTowardNetWorth && (
                          <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] uppercase tracking-wide text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
                            {t("networth.not_counted_badge")}
                          </span>
                        )}
                        {!it.active && <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] uppercase tracking-wide text-gray-500 dark:bg-chip dark:text-gray-400">{t("common.inactive")}</span>}
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
                      <Button
                        variant="ghost"
                        className="px-2"
                        title={t("common.edit")}
                        onClick={() =>
                          startEdit({
                            id: it.id,
                            name: it.name,
                            type: it.type,
                            active: it.active,
                            countsTowardNetWorth: it.countsTowardNetWorth,
                            amortizable: it.amortizable,
                            chargeDay: it.chargeDay?.toString() ?? "",
                            initialBalance: "",
                            initialBalanceDate: isoToday(),
                          })
                        }
                      >
                        <span aria-hidden>✏️</span>
                      </Button>
                      <Button variant="ghost" className="px-2" title={t("common.delete")} onClick={() => { if (window.confirm(t("common.delete") + "?")) void del.mutate(it.id); }}><span aria-hidden>🗑️</span></Button>
                    </div>
                  </div>
                  {openAmort === it.id && <AmortizationPanel liability={it} onEditorOpen={() => startEdit(null)} />}
                </li>
              ) : (
                <NamedValueCard
                  key={it.id}
                  kind="liability"
                  item={{
                    id: it.id,
                    name: it.name,
                    active: it.active,
                    liabilityType: it.type,
                    countsTowardNetWorth: it.countsTowardNetWorth,
                    amortizable: it.amortizable,
                    chargeDay: it.chargeDay,
                    latestValue: it.latestBalance,
                    latestValueDate: it.latestBalanceDate,
                  }}
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
