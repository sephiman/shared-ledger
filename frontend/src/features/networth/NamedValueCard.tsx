import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import type { AssetType, ValueEntry } from "@/api/networth";
import { Button, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";

const ASSET_TYPES: AssetType[] = ["property", "vehicle", "other"];

type Mut<T> = { mutateAsync: (v: T) => Promise<unknown>; isPending: boolean };

export interface DetailsDraft {
  name: string;
  active: boolean;
  type?: AssetType;
  amortizable?: boolean;
  chargeDay?: string;
}

export interface NamedItem {
  id: string;
  name: string;
  active: boolean;
  type?: AssetType;
  amortizable?: boolean;
  chargeDay?: number | null;
  latestValue?: string | null;
  latestValueDate?: string | null;
}

/**
 * One card for a named asset or a simple (non-amortizable) liability. Shared layout: a header with
 * the current value, then the dated value series as a list. Only one editor is open at a time —
 * either the item details or the value history. Asset adds to net worth; liability subtracts.
 */
export function NamedValueCard({
  kind,
  item,
  useValues,
  useAdd,
  useUpdate,
  useDelete,
  onSaveDetails,
  onDelete,
}: {
  kind: "asset" | "liability";
  item: NamedItem;
  useValues: (hid: string, id: string, enabled?: boolean) => { data?: ValueEntry[] };
  useAdd: (hid: string, id: string) => Mut<{ date: string; value: string }>;
  useUpdate: (hid: string, id: string) => Mut<{ entryId: string; date: string; value: string }>;
  useDelete: (hid: string, id: string) => Mut<string>;
  onSaveDetails: (d: DetailsDraft) => Promise<void>;
  onDelete: () => void;
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const { data: entries = [] } = useValues(hid, item.id);
  const add = useAdd(hid, item.id);
  const update = useUpdate(hid, item.id);
  const del = useDelete(hid, item.id);
  const money = (v: string) => formatMoney(v, household.currency, i18n.language);

  // Single editor: details, or a value row (new / an entry id), or nothing.
  const [mode, setMode] = useState<"view" | "details">("view");
  const [valueEditor, setValueEditor] = useState<null | { entryId?: string; date: string; value: string }>(null);
  const editingDetails = mode === "details";
  const editingValue = valueEditor !== null;

  function openDetails() {
    setValueEditor(null);
    setMode("details");
  }
  function openAddValue() {
    setMode("view");
    setValueEditor({ date: isoToday(), value: "" });
  }
  function openEditValue(e: ValueEntry) {
    setMode("view");
    setValueEditor({ entryId: e.id, date: e.date, value: e.value });
  }

  async function saveValue() {
    if (!valueEditor) return;
    if (!valueEditor.date || valueEditor.value.trim() === "" || Number.isNaN(Number(valueEditor.value))) return;
    if (valueEditor.entryId) await update.mutateAsync({ entryId: valueEditor.entryId, date: valueEditor.date, value: valueEditor.value });
    else await add.mutateAsync({ date: valueEditor.date, value: valueEditor.value });
    setValueEditor(null);
  }

  const addsHint = kind === "asset" ? t("networth.adds_to_net_worth") : t("networth.subtracts_from_net_worth");

  return (
    <li className="rounded-md border border-border p-3 dark:border-gray-700">
      {editingDetails ? (
        <DetailsForm kind={kind} item={item} onCancel={() => setMode("view")} onSave={async (d) => { await onSaveDetails(d); setMode("view"); }} />
      ) : (
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1 break-words">
            <p className="font-medium">
              {item.name}
              {kind === "asset" && item.type && (
                <span className="ml-2 text-xs font-normal text-gray-500 dark:text-gray-400">{t(`networth.asset_type_${item.type}`)}</span>
              )}
              {!item.active && (
                <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] uppercase tracking-wide text-gray-500 dark:bg-gray-800 dark:text-gray-400">
                  {t("common.inactive")}
                </span>
              )}
            </p>
            {item.latestValue != null ? (
              <p className="mt-0.5">
                <span className="font-mono text-lg tabular-nums">{money(item.latestValue)}</span>
                {item.latestValueDate && <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">· {formatDate(item.latestValueDate, i18n.language)}</span>}
              </p>
            ) : (
              <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{t("networth.no_value_yet")}</p>
            )}
            <p className="text-[11px] text-gray-400 dark:text-gray-500">{addsHint}</p>
          </div>
          <div className="flex gap-1">
            <Button variant="ghost" className="px-2" title={t("networth.edit_details")} onClick={openDetails}><span aria-hidden>✏️</span></Button>
            <Button variant="ghost" className="px-2" title={t("common.delete")} onClick={() => { if (window.confirm(t("common.delete") + "?")) onDelete(); }}><span aria-hidden>🗑️</span></Button>
          </div>
        </div>
      )}

      {/* Value history */}
      <div className="mt-3">
        <div className="mb-1 flex items-center justify-between">
          <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.value_history")}</p>
          {!editingDetails && !editingValue && (
            <Button className="px-2 py-1 text-xs" variant="secondary" onClick={openAddValue}>{t("networth.add_value")}</Button>
          )}
        </div>

        {editingValue && (
          <div className="mb-2 flex flex-wrap items-end gap-2 rounded border border-border p-2 dark:border-gray-700">
            <div>
              <Label>{t("networth.value_date")}</Label>
              <Input type="date" value={valueEditor!.date} onChange={(e) => setValueEditor({ ...valueEditor!, date: e.target.value })} />
            </div>
            <div>
              <Label>{t("networth.value_amount")}</Label>
              <Input type="number" step="0.01" value={valueEditor!.value} onChange={(e) => setValueEditor({ ...valueEditor!, value: e.target.value })} />
            </div>
            <Button className="px-2 py-1 text-xs" onClick={saveValue} disabled={add.isPending || update.isPending}>{t("common.save")}</Button>
            <Button className="px-2 py-1 text-xs" variant="secondary" onClick={() => setValueEditor(null)}>{t("common.cancel")}</Button>
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
                  {/* Editing an entry is a value editor too — mutually exclusive with details editing. */}
                  <Button variant="ghost" className="px-2" title={t("common.edit")} disabled={editingDetails} onClick={() => openEditValue(e)}><span aria-hidden>✏️</span></Button>
                  <Button variant="ghost" className="px-2" title={t("common.delete")} disabled={editingDetails} onClick={() => { if (window.confirm(t("common.delete") + "?")) void del.mutateAsync(e.id); }}><span aria-hidden>🗑️</span></Button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </li>
  );
}

function DetailsForm({
  kind,
  item,
  onCancel,
  onSave,
}: {
  kind: "asset" | "liability";
  item: NamedItem;
  onCancel: () => void;
  onSave: (d: DetailsDraft) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState(item.name);
  const [active, setActive] = useState(item.active);
  const [type, setType] = useState<AssetType>(item.type ?? "property");
  const [amortizable, setAmortizable] = useState(item.amortizable ?? false);
  const [chargeDay, setChargeDay] = useState(item.chargeDay?.toString() ?? "");
  const [nameError, setNameError] = useState<string | null>(null);
  const [chargeError, setChargeError] = useState<string | null>(null);

  async function submit() {
    if (!name.trim()) { setNameError(t("errors.field_required")); return; }
    if (kind === "liability" && amortizable) {
      const cd = chargeDay ? Number(chargeDay) : null;
      if (cd == null || cd < 1 || cd > 31) { setChargeError(t("networth.charge_day_required")); return; }
    }
    await onSave({ name, active, type: kind === "asset" ? type : undefined, amortizable: kind === "liability" ? amortizable : undefined, chargeDay });
  }

  return (
    <div className="space-y-3">
      <div>
        <Label>{kind === "asset" ? t("networth.asset_name") : t("networth.liability_name")}</Label>
        <Input value={name} invalid={!!nameError} onChange={(e) => { setName(e.target.value); if (nameError) setNameError(null); }} />
        <FieldError message={nameError} />
      </div>
      {kind === "asset" && (
        <div>
          <Label>{t("networth.asset_type")}</Label>
          <Select value={type} onChange={(e) => setType(e.target.value as AssetType)}>
            {ASSET_TYPES.map((tp) => <option key={tp} value={tp}>{t(`networth.asset_type_${tp}`)}</option>)}
          </Select>
        </div>
      )}
      <div className="flex items-center gap-2">
        <input id={`active-${item.id}`} type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
        <Label htmlFor={`active-${item.id}`} className="mb-0">{kind === "asset" ? t("networth.asset_active") : t("networth.liability_active")}</Label>
        <span className="text-xs text-gray-400 dark:text-gray-500">{t("networth.inactive_hint")}</span>
      </div>
      {kind === "liability" && (
        <>
          <div className="flex items-center gap-2">
            <input id={`amort-${item.id}`} type="checkbox" checked={amortizable} onChange={(e) => setAmortizable(e.target.checked)} />
            <Label htmlFor={`amort-${item.id}`} className="mb-0">{t("networth.amortizable")}</Label>
          </div>
          {amortizable && (
            <div>
              <Label>{t("networth.charge_day")}</Label>
              <Input type="number" min={1} max={31} invalid={!!chargeError} value={chargeDay} onChange={(e) => { setChargeDay(e.target.value); if (chargeError) setChargeError(null); }} />
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("networth.charge_day_hint")}</p>
              <FieldError message={chargeError} />
            </div>
          )}
        </>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel}>{t("common.cancel")}</Button>
        <Button onClick={submit}>{t("common.save")}</Button>
      </div>
    </div>
  );
}
