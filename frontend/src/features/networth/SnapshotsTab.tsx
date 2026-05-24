import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAssetClasses } from "@/api/catalog";
import {
  useCreateSnapshot,
  useDeleteSnapshot,
  useLiabilities,
  useSnapshotPrefill,
  useSnapshots,
  type AssetValue,
  type LiabilityBalance,
} from "@/api/networth";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Textarea } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";

export function SnapshotsTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: snapshots = [] } = useSnapshots(household.householdId);
  const { data: prefill } = useSnapshotPrefill(household.householdId);
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: liabilities = [] } = useLiabilities(household.householdId);
  const create = useCreateSnapshot(household.householdId);
  const del = useDeleteSnapshot(household.householdId);

  const [editing, setEditing] = useState<boolean>(false);
  const [date, setDate] = useState(isoToday());
  const [note, setNote] = useState("");
  const [assets, setAssets] = useState<Record<string, string>>({});
  const [liab, setLiab] = useState<Record<string, string>>({});
  const [confirmLarge, setConfirmLarge] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dateError, setDateError] = useState<string | null>(null);

  function startNew() {
    setEditing(true);
    setDate(isoToday());
    setNote("");
    const a: Record<string, string> = {};
    for (const cls of assetClasses) {
      const prev = prefill?.previous?.assets.find((x) => x.assetClassCode === cls.code);
      a[cls.code] = prev?.value ?? "0";
    }
    setAssets(a);
    const l: Record<string, string> = {};
    for (const liability of liabilities.filter((x) => x.active)) {
      const prev = prefill?.previous?.liabilities.find((x) => x.liabilityId === liability.id);
      l[liability.id] = prev?.balance ?? "0";
    }
    setLiab(l);
    setConfirmLarge(false);
    setError(null);
    setDateError(null);
  }

  function delta(prev: string | undefined, next: string): { abs: string; pct: string; large: boolean } {
    const p = new Decimal(prev ?? 0);
    const n = new Decimal(next || 0);
    const abs = n.minus(p);
    if (p.isZero()) return { abs: abs.toFixed(2), pct: n.isZero() ? "0" : "—", large: !n.isZero() };
    const pct = abs.div(p.abs()).times(100);
    return { abs: abs.toFixed(2), pct: pct.toFixed(1) + "%", large: pct.abs().gt(50) };
  }

  async function save() {
    setError(null);
    if (!date) {
      setDateError(t("errors.field_required"));
      return;
    }
    setDateError(null);
    const assetItems: AssetValue[] = assetClasses.map((cls) => ({ assetClassCode: cls.code, value: assets[cls.code] || "0" }));
    const liabItems: LiabilityBalance[] = liabilities.filter((x) => x.active).map((x) => ({ liabilityId: x.id, balance: liab[x.id] || "0" }));
    try {
      await create.mutateAsync({
        snapshotDate: date,
        note: note || null,
        assets: assetItems,
        liabilities: liabItems,
        confirmLargeChanges: confirmLarge,
      });
      setEditing(false);
    } catch (err) {
      const api = asApiError(err);
      if (api.code === "SNAPSHOT_REQUIRES_DELTA_CONFIRMATION") {
        setConfirmLarge(false);
      }
      setError(t(`errors.${api.code}`, api.message));
    }
  }

  const previousAssets = useMemo(() => {
    const map: Record<string, string> = {};
    prefill?.previous?.assets.forEach((a) => { map[a.assetClassCode] = a.value; });
    return map;
  }, [prefill]);
  const previousLiabilities = useMemo(() => {
    const map: Record<string, string> = {};
    prefill?.previous?.liabilities.forEach((l) => { map[l.liabilityId] = l.balance; });
    return map;
  }, [prefill]);

  const anyLarge = useMemo(() => {
    if (!prefill?.previous) return false;
    for (const cls of assetClasses) {
      const d = delta(previousAssets[cls.code], assets[cls.code] ?? "0");
      if (d.large) return true;
    }
    for (const l of liabilities.filter((x) => x.active)) {
      const d = delta(previousLiabilities[l.id], liab[l.id] ?? "0");
      if (d.large) return true;
    }
    return false;
  }, [assets, liab, assetClasses, liabilities, previousAssets, previousLiabilities, prefill]);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500">{t("networth.snapshots_description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/snapshots/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={startNew}>{t("networth.new_snapshot")}</Button>
        </div>
      </div>

      {editing && (
        <Card>
          <CardHeader>
            <p className="font-medium">{t("networth.new_snapshot")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <Label>{t("networth.snapshot_date")}</Label>
                <Input
                  type="date"
                  value={date}
                  invalid={!!dateError}
                  onChange={(e) => { setDate(e.target.value); if (dateError) setDateError(null); }}
                />
                <FieldError message={dateError} />
              </div>
              <div>
                <Label>{t("networth.snapshot_note")}</Label>
                <Input value={note} onChange={(e) => setNote(e.target.value)} />
              </div>
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">{t("networth.total_assets")}</p>
              <div className="space-y-2">
                {assetClasses.map((cls) => {
                  const d = delta(previousAssets[cls.code], assets[cls.code] ?? "0");
                  return (
                    <div key={cls.code} className="flex items-center gap-2">
                      <Label className="mb-0 w-32">{t(`asset.${cls.code}`)}</Label>
                      <Input
                        type="number"
                        step="0.01"
                        value={assets[cls.code] ?? ""}
                        onChange={(e) => setAssets({ ...assets, [cls.code]: e.target.value })}
                      />
                      <span className={`w-24 text-right text-xs ${d.large ? "text-amber-600" : "text-gray-500"}`}>{d.abs} ({d.pct})</span>
                    </div>
                  );
                })}
              </div>
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">{t("networth.total_liabilities")}</p>
              <div className="space-y-2">
                {liabilities.filter((x) => x.active).map((l) => {
                  const d = delta(previousLiabilities[l.id], liab[l.id] ?? "0");
                  return (
                    <div key={l.id} className="flex items-center gap-2">
                      <Label className="mb-0 w-32">{l.name}</Label>
                      <Input
                        type="number"
                        step="0.01"
                        value={liab[l.id] ?? ""}
                        onChange={(e) => setLiab({ ...liab, [l.id]: e.target.value })}
                      />
                      <span className={`w-24 text-right text-xs ${d.large ? "text-amber-600" : "text-gray-500"}`}>{d.abs} ({d.pct})</span>
                    </div>
                  );
                })}
              </div>
            </div>
            {anyLarge && (
              <div className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
                <p>{t("networth.delta_warning")}</p>
                <label className="mt-2 inline-flex items-center gap-2">
                  <input type="checkbox" checked={confirmLarge} onChange={(e) => setConfirmLarge(e.target.checked)} />
                  {t("networth.i_confirm")}
                </label>
              </div>
            )}
            <FieldError message={error} />
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setEditing(false)}>{t("common.cancel")}</Button>
              <Button onClick={save} disabled={anyLarge && !confirmLarge}>{t("common.save")}</Button>
            </div>
          </CardBody>
        </Card>
      )}

      <Card>
        <CardBody>
          {snapshots.length === 0 ? (
            <p className="text-gray-500">{t("common.empty")}</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">{t("networth.snapshot_date")}</th>
                  <th className="text-right">{t("networth.total_assets")}</th>
                  <th className="text-right">{t("networth.total_liabilities")}</th>
                  <th className="text-right">{t("networth.net_worth")}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {snapshots.slice().reverse().map((s) => (
                  <tr key={s.id} className="border-t border-border">
                    <td className="py-2">{formatDate(s.snapshotDate, i18n.language)}</td>
                    <td className="text-right">{formatMoney(s.totalAssets, household.currency, i18n.language)}</td>
                    <td className="text-right">{formatMoney(s.totalLiabilities, household.currency, i18n.language)}</td>
                    <td className="text-right font-medium">{formatMoney(s.netWorth, household.currency, i18n.language)}</td>
                    <td className="text-right">
                      <Button
                        variant="ghost"
                        onClick={() => {
                          if (window.confirm(t("common.delete") + "?")) void del.mutate(s.id);
                        }}
                      >
                        {t("common.delete")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
