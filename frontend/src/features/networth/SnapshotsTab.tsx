import { Fragment, useEffect, useMemo, useRef, useState } from "react";
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
  useUpdateSnapshot,
  type AssetValue,
  type LiabilityBalance,
  type Snapshot,
} from "@/api/networth";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";

type PanelMode = { kind: "closed" } | { kind: "create" } | { kind: "edit"; s: Snapshot };

export function SnapshotsTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: snapshots = [] } = useSnapshots(household.householdId);
  const { data: prefill } = useSnapshotPrefill(household.householdId);
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: liabilities = [] } = useLiabilities(household.householdId);
  const create = useCreateSnapshot(household.householdId);
  const update = useUpdateSnapshot(household.householdId);
  const del = useDeleteSnapshot(household.householdId);

  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [date, setDate] = useState(isoToday());
  const [note, setNote] = useState("");
  const [assets, setAssets] = useState<Record<string, string>>({});
  const [liab, setLiab] = useState<Record<string, string>>({});
  const [confirmLarge, setConfirmLarge] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dateError, setDateError] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  function toggleExpanded(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  useEffect(() => {
    if (panel.kind === "edit") {
      panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [panel]);

  function startNew() {
    setPanel({ kind: "create" });
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

  function startEdit(s: Snapshot) {
    setPanel({ kind: "edit", s });
    setDate(s.snapshotDate);
    setNote(s.note ?? "");
    const a: Record<string, string> = {};
    for (const cls of assetClasses) {
      const v = s.assets.find((x) => x.assetClassCode === cls.code);
      a[cls.code] = v?.value ?? "0";
    }
    setAssets(a);
    const l: Record<string, string> = {};
    const liabilityIdsInSnapshot = new Set(s.liabilities.map((x) => x.liabilityId));
    for (const balance of s.liabilities) {
      l[balance.liabilityId] = balance.balance;
    }
    // include any currently-active liabilities that aren't in the snapshot yet
    for (const liability of liabilities.filter((x) => x.active)) {
      if (!liabilityIdsInSnapshot.has(liability.id)) l[liability.id] = "0";
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

  function closePanel() {
    setPanel({ kind: "closed" });
  }

  async function save() {
    setError(null);
    if (!date) {
      setDateError(t("errors.field_required"));
      return;
    }
    setDateError(null);
    const assetItems: AssetValue[] = assetClasses.map((cls) => ({ assetClassCode: cls.code, value: assets[cls.code] || "0" }));
    const liabIds = panel.kind === "edit"
      ? Object.keys(liab)
      : liabilities.filter((x) => x.active).map((x) => x.id);
    const liabItems: LiabilityBalance[] = liabIds.map((id) => ({ liabilityId: id, balance: liab[id] || "0" }));
    try {
      if (panel.kind === "edit") {
        await update.mutateAsync({
          id: panel.s.id,
          input: {
            snapshotDate: date,
            note: note || null,
            assets: assetItems,
            liabilities: liabItems,
            confirmLargeChanges: true,
          },
        });
      } else {
        await create.mutateAsync({
          snapshotDate: date,
          note: note || null,
          assets: assetItems,
          liabilities: liabItems,
          confirmLargeChanges: confirmLarge,
        });
      }
      closePanel();
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
    if (panel.kind !== "create") return false;
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
  }, [panel.kind, assets, liab, assetClasses, liabilities, previousAssets, previousLiabilities, prefill]);

  const liabilityName = (id: string) => liabilities.find((x) => x.id === id)?.name ?? id;
  const liabilityIdsInForm = panel.kind === "edit"
    ? Object.keys(liab)
    : liabilities.filter((x) => x.active).map((x) => x.id);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.snapshots_description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/snapshots/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={panel.kind === "create" ? closePanel : startNew}>
            {panel.kind === "create" ? t("common.cancel") : t("networth.new_snapshot")}
          </Button>
        </div>
      </div>

      {panel.kind !== "closed" && (
        <div ref={panelRef}>
        <Card>
          <CardHeader>
            <p className="font-medium">{panel.kind === "edit" ? t("common.edit") : t("networth.new_snapshot")}</p>
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
                  const d = panel.kind === "create" ? delta(previousAssets[cls.code], assets[cls.code] ?? "0") : null;
                  return (
                    <div key={cls.code} className="flex items-center gap-2">
                      <Label className="mb-0 w-32">{t(`asset.${cls.code}`)}</Label>
                      <Input
                        type="number"
                        step="0.01"
                        value={assets[cls.code] ?? ""}
                        onChange={(e) => setAssets({ ...assets, [cls.code]: e.target.value })}
                      />
                      {d && (
                        <span className={`w-24 text-right text-xs ${d.large ? "text-amber-600" : "text-gray-500 dark:text-gray-400"}`}>{d.abs} ({d.pct})</span>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">{t("networth.total_liabilities")}</p>
              <div className="space-y-2">
                {liabilityIdsInForm.map((id) => {
                  const d = panel.kind === "create" ? delta(previousLiabilities[id], liab[id] ?? "0") : null;
                  return (
                    <div key={id} className="flex items-center gap-2">
                      <Label className="mb-0 w-32">{liabilityName(id)}</Label>
                      <Input
                        type="number"
                        step="0.01"
                        value={liab[id] ?? ""}
                        onChange={(e) => setLiab({ ...liab, [id]: e.target.value })}
                      />
                      {d && (
                        <span className={`w-24 text-right text-xs ${d.large ? "text-amber-600" : "text-gray-500 dark:text-gray-400"}`}>{d.abs} ({d.pct})</span>
                      )}
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
              <Button variant="secondary" onClick={closePanel}>{t("common.cancel")}</Button>
              <Button onClick={save} disabled={anyLarge && !confirmLarge}>{t("common.save")}</Button>
            </div>
          </CardBody>
        </Card>
        </div>
      )}

      <Card>
        <CardBody>
          {snapshots.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 md:hidden">
                {snapshots.slice().reverse().map((s) => {
                  const isOpen = expandedIds.has(s.id);
                  return (
                    <li key={s.id} className="rounded-md border border-border dark:border-gray-700">
                      <button
                        type="button"
                        onClick={() => toggleExpanded(s.id)}
                        aria-expanded={isOpen}
                        className="block w-full p-3 text-left"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0 flex-1">
                            <p className="font-medium">
                              <span className="mr-1 inline-block w-3 text-xs text-gray-400" aria-hidden>{isOpen ? "▾" : "▸"}</span>
                              {formatDate(s.snapshotDate, i18n.language)}
                            </p>
                            {s.note && (
                              <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">{s.note}</p>
                            )}
                            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                              {t("networth.total_assets")}: {formatMoney(s.totalAssets, household.currency, i18n.language)} ·
                              {" "}
                              {t("networth.total_liabilities")}: {formatMoney(s.totalLiabilities, household.currency, i18n.language)}
                            </p>
                          </div>
                          <div className="flex flex-col items-end gap-1">
                            <span className="font-medium">{formatMoney(s.netWorth, household.currency, i18n.language)}</span>
                            <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
                              <Button
                                variant="ghost"
                                className="px-2"
                                aria-label={t("common.edit")}
                                title={t("common.edit")}
                                onClick={() => startEdit(s)}
                              >
                                <span aria-hidden>✏️</span>
                              </Button>
                              <Button
                                variant="ghost"
                                className="px-2"
                                aria-label={t("common.delete")}
                                title={t("common.delete")}
                                onClick={() => {
                                  if (window.confirm(t("common.delete") + "?")) void del.mutate(s.id);
                                }}
                              >
                                <span aria-hidden>🗑️</span>
                              </Button>
                            </div>
                          </div>
                        </div>
                      </button>
                      {isOpen && (
                        <div className="border-t border-border px-3 py-2 dark:border-gray-700">
                          <SnapshotComposition snapshot={s} liabilities={liabilities} />
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
              <table className="hidden w-full text-sm md:table">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="w-6 py-2"></th>
                    <th>{t("networth.snapshot_date")}</th>
                    <th>{t("networth.snapshot_note")}</th>
                    <th className="text-right">{t("networth.total_assets")}</th>
                    <th className="text-right">{t("networth.total_liabilities")}</th>
                    <th className="text-right">{t("networth.net_worth")}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {snapshots.slice().reverse().map((s) => {
                    const isOpen = expandedIds.has(s.id);
                    return (
                      <Fragment key={s.id}>
                        <tr
                          className="cursor-pointer border-t border-border hover:bg-gray-50 dark:hover:bg-gray-700/40"
                          onClick={() => toggleExpanded(s.id)}
                          aria-expanded={isOpen}
                        >
                          <td className="py-2 text-center text-xs text-gray-400" aria-hidden>{isOpen ? "▾" : "▸"}</td>
                          <td className="py-2">{formatDate(s.snapshotDate, i18n.language)}</td>
                          <td className="text-gray-600 dark:text-gray-300">{s.note ?? "—"}</td>
                          <td className="text-right">{formatMoney(s.totalAssets, household.currency, i18n.language)}</td>
                          <td className="text-right">{formatMoney(s.totalLiabilities, household.currency, i18n.language)}</td>
                          <td className="text-right font-medium">{formatMoney(s.netWorth, household.currency, i18n.language)}</td>
                          <td className="text-right" onClick={(e) => e.stopPropagation()}>
                            <div className="inline-flex gap-1">
                              <Button
                                variant="ghost"
                                className="px-2"
                                aria-label={t("common.edit")}
                                title={t("common.edit")}
                                onClick={() => startEdit(s)}
                              >
                                <span aria-hidden>✏️</span>
                              </Button>
                              <Button
                                variant="ghost"
                                className="px-2"
                                aria-label={t("common.delete")}
                                title={t("common.delete")}
                                onClick={() => {
                                  if (window.confirm(t("common.delete") + "?")) void del.mutate(s.id);
                                }}
                              >
                                <span aria-hidden>🗑️</span>
                              </Button>
                            </div>
                          </td>
                        </tr>
                        {isOpen && (
                          <tr className="border-t border-border bg-gray-50/50 dark:bg-gray-900/30">
                            <td></td>
                            <td colSpan={6} className="px-2 py-3">
                              <SnapshotComposition snapshot={s} liabilities={liabilities} />
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function SnapshotComposition({
  snapshot,
  liabilities,
}: {
  snapshot: Snapshot;
  liabilities: { id: string; name: string }[];
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const liabilityName = (id: string) => liabilities.find((x) => x.id === id)?.name ?? id;
  return (
    <div className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
      <div>
        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
          {t("networth.total_assets")}
        </p>
        {snapshot.assets.length === 0 ? (
          <p className="text-xs text-gray-500 dark:text-gray-400">—</p>
        ) : (
          <ul className="space-y-0.5">
            {snapshot.assets.map((a) => (
              <li key={a.assetClassCode} className="flex justify-between gap-3">
                <span className="text-gray-600 dark:text-gray-300">{t(`asset.${a.assetClassCode}`, a.assetClassCode)}</span>
                <span className="font-mono tabular-nums">{formatMoney(a.value, household.currency, i18n.language)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div>
        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
          {t("networth.total_liabilities")}
        </p>
        {snapshot.liabilities.length === 0 ? (
          <p className="text-xs text-gray-500 dark:text-gray-400">—</p>
        ) : (
          <ul className="space-y-0.5">
            {snapshot.liabilities.map((l) => (
              <li key={l.liabilityId} className="flex justify-between gap-3">
                <span className="text-gray-600 dark:text-gray-300">{liabilityName(l.liabilityId)}</span>
                <span className="font-mono tabular-nums">{formatMoney(l.balance, household.currency, i18n.language)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
