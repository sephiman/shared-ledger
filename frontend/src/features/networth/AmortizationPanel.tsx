import { useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import type { Liability } from "@/api/networth";
import {
  useAddRevision,
  useAmortizationSchedule,
  useCreatePart,
  useDeletePart,
  useDeletePrepayment,
  useDeleteRevision,
  useRecordPrepayment,
  useSimulatePrepayment,
  useReAnchor,
  useUpdatePart,
  type AmortizationMethod,
  type PartInput,
  type PartSchedule,
  type PrepaymentMode,
  type SimulationResult,
  type StartMode,
} from "@/api/amortization";
import { Button, Card, CardBody, Input, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";
import { computeDerived, type Driver } from "./amortizationCompute";

const METHODS: AmortizationMethod[] = ["french", "german", "interest_only", "zero"];

// Inline text actions in a part row: read as clickable links (hand cursor, underline on hover)
// rather than the arrow-cursor plain <button> default.
const actionLink =
  "cursor-pointer font-medium text-primary underline-offset-2 hover:underline focus:outline-none focus:ring-2 focus:ring-primary rounded";

type EditingPart = {
  id?: string;
  label: string;
  method: AmortizationMethod;
  startMode: StartMode;
  principal: string;
  rate: string;
  startDate: string;
  driver: Driver;
  term: string;
  endDate: string;
  instalment: string;
};

const blankPart = (): EditingPart => ({
  label: "", method: "french", startMode: "current_balance", principal: "", rate: "0",
  startDate: isoToday(), driver: "term", term: "", endDate: "", instalment: "",
});

export function AmortizationPanel({ liability, onEditorOpen }: { liability: Liability; onEditorOpen?: () => void }) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const { data: schedule } = useAmortizationSchedule(hid, liability.id);
  const createPart = useCreatePart(hid, liability.id);
  const updatePart = useUpdatePart(hid, liability.id);
  const delPart = useDeletePart(hid, liability.id);
  const [editing, setEditing] = useState<EditingPart | null>(null);
  // Track the part being prepaid by id and re-derive it from the live schedule, so recording/deleting
  // a prepayment (which refetches the schedule) refreshes the dialog instead of showing a stale snapshot.
  const [prepayPartId, setPrepayPartId] = useState<string | null>(null);
  const money = (v: string | number) => formatMoney(String(v), household.currency, i18n.language);
  const parts = schedule?.parts ?? [];
  const prepayPart = parts.find((p) => p.partId === prepayPartId) ?? null;

  function openPartEditor(next: EditingPart) {
    onEditorOpen?.(); // one editor at a time — close the loan-edit card in the parent
    setPrepayPartId(null);
    setEditing(next);
  }
  function openPrepay(part: PartSchedule) {
    onEditorOpen?.();
    setEditing(null);
    setPrepayPartId(part.partId);
  }

  function editFrom(part: PartSchedule): EditingPart {
    return {
      id: part.partId,
      label: part.label ?? "",
      method: part.method,
      startMode: part.startMode,
      principal: part.originalPrincipal,
      rate: part.annualRate,
      startDate: part.startDate,
      driver: part.instalmentInput != null ? "instalment" : "term",
      term: part.termMonths?.toString() ?? "",
      endDate: part.payoffDate ?? "",
      instalment: part.instalmentInput ?? "",
    };
  }

  async function savePart() {
    if (!editing) return;
    const input: PartInput = {
      label: editing.label.trim() || null,
      method: editing.method,
      startMode: editing.startMode,
      originalPrincipal: editing.principal,
      annualRate: editing.rate,
      startDate: editing.startDate,
      termMonths: editing.driver === "term" && editing.term ? Number(editing.term) : null,
      endDate: editing.driver === "endDate" && editing.endDate ? editing.endDate : null,
      instalment: editing.driver === "instalment" && editing.instalment ? editing.instalment : null,
    };
    if (editing.id) await updatePart.mutateAsync({ id: editing.id, input });
    else await createPart.mutateAsync(input);
    setEditing(null);
  }

  return (
    <div className="mt-3 rounded-md border border-primary/40 bg-primary/5 p-3 dark:border-primary/30 dark:bg-primary/10">
      {/* Loan header */}
      <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-sm font-semibold">{liability.name}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {t("common.active")}: {liability.active ? t("common.yes") : t("common.no")} · {t("networth.charge_day")}: {schedule?.chargeDay ?? liability.chargeDay ?? "—"}
          </p>
        </div>
        <Button className="px-2 py-1 text-xs" onClick={() => openPartEditor(blankPart())}>{t("networth.add_part")}</Button>
      </div>

      {parts.length > 0 && schedule && (
        <div className="mb-3 rounded-md border border-border bg-white p-2 dark:border-gray-700 dark:bg-gray-800">
          <div className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-3">
            <Stat label={t("networth.current_balance")} value={money(schedule.currentBalance)} />
            <Stat label={t("networth.monthly_instalment")} value={money(schedule.monthlyInstalment)} />
            <Stat label={t("networth.progress")} value={`${(Number(schedule.progress) * 100).toFixed(1)}%`} />
            <Stat label={t("networth.principal_paid")} value={money(schedule.principalPaid)} />
            <Stat label={t("networth.interest_paid")} value={money(schedule.interestPaid)} />
            <Stat label={t("networth.interest_remaining")} value={money(schedule.interestRemaining)} />
            <Stat label={t("networth.total_interest")} value={money(schedule.totalInterest)} />
          </div>
          <div className="mt-2 h-1.5 w-full overflow-hidden rounded bg-gray-200 dark:bg-gray-700">
            <div className="h-full bg-primary" style={{ width: `${Math.min(100, Math.max(0, Number(schedule.progress) * 100))}%` }} />
          </div>
        </div>
      )}
      <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("networth.loan_intro")}</p>

      {/* Parts list (compact) */}
      {parts.length > 0 && (
        <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.saved_parts")}</p>
      )}
      {parts.map((part) => (
        <PartRow
          key={part.partId}
          liabilityId={liability.id}
          part={part}
          money={money}
          onEdit={() => openPartEditor(editFrom(part))}
          onDelete={() => { if (window.confirm(t("common.delete") + "?")) void delPart.mutate(part.partId); }}
          onPrepay={() => openPrepay(part)}
        />
      ))}
      {parts.length === 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.no_parts")}</p>
      )}

      {/* Part editor — side panel */}
      {editing && (
        <SidePanel title={editing.id ? t("networth.edit_part") : t("networth.add_part")} onClose={() => setEditing(null)}>
          <PartForm value={editing} onChange={setEditing} onCancel={() => setEditing(null)} onSave={savePart} />
        </SidePanel>
      )}

      {/* Prepayment — dialog (part re-derived from the live schedule so it refreshes on record/delete) */}
      {prepayPart && (
        <PrepaymentDialog liabilityId={liability.id} part={prepayPart} money={money} onClose={() => setPrepayPartId(null)} />
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[10px] uppercase tracking-wide text-gray-500 dark:text-gray-400">{label}</p>
      <p className="font-mono tabular-nums">{value}</p>
    </div>
  );
}

/** Slide-in overlay from the right; keeps the list/totals in place and puts one form in focus. */
function SidePanel({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true">
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />
      <div className="relative z-10 h-full w-full max-w-md overflow-y-auto bg-white shadow-xl dark:bg-gray-900">
        <div className="flex items-center justify-between border-b border-border p-3 dark:border-gray-700">
          <p className="font-medium">{title}</p>
          <button aria-label="close" className="px-2 text-gray-500" onClick={onClose}>✕</button>
        </div>
        <div className="p-3">{children}</div>
      </div>
    </div>
  );
}

function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true">
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />
      <div className="relative z-10 w-full max-w-lg overflow-y-auto rounded-lg bg-white shadow-xl dark:bg-gray-900">
        <div className="flex items-center justify-between border-b border-border p-3 dark:border-gray-700">
          <p className="font-medium">{title}</p>
          <button aria-label="close" className="px-2 text-gray-500" onClick={onClose}>✕</button>
        </div>
        <div className="p-3">{children}</div>
      </div>
    </div>
  );
}

function PartRow({
  liabilityId, part, money, onEdit, onDelete, onPrepay,
}: {
  liabilityId: string;
  part: PartSchedule;
  money: (v: string | number) => string;
  onEdit: () => void;
  onDelete: () => void;
  onPrepay: () => void;
}) {
  const { t, i18n } = useTranslation();
  const [show, setShow] = useState<null | "revisions" | "schedule" | "anchor">(null);
  const isOrigin = part.startMode === "origin";
  return (
    <div className="mb-2 rounded-md border border-border bg-white p-2 text-sm dark:border-gray-700 dark:bg-gray-800">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="font-medium">
            {part.label || t(`networth.method_${part.method}`)}
            {isOrigin && <span className="ml-2 text-[10px] uppercase tracking-wide text-gray-400 dark:text-gray-500">{t("networth.start_mode_origin")}</span>}
          </p>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {t(`networth.method_${part.method}`)} · {money(part.originalPrincipal)} · {part.annualRate}% · {money(part.instalment)}/{t("networth.per_month")}
            {part.payoffDate ? ` · ${t("networth.payoff")}: ${formatDate(part.payoffDate, i18n.language)}` : ""}
          </p>
          <p className="text-xs text-gray-400 dark:text-gray-500">
            {t("networth.current_balance")}: <span className="font-mono">{money(part.currentBalance)}</span>
            {isOrigin && <span className="ml-1">({t("networth.computed")})</span>}
          </p>
        </div>
        <div className="flex gap-1">
          <Button variant="ghost" className="px-2" title={t("common.edit")} onClick={onEdit}><span aria-hidden>✏️</span></Button>
          <Button variant="ghost" className="px-2" title={t("common.delete")} onClick={onDelete}><span aria-hidden>🗑️</span></Button>
        </div>
      </div>
      {/* Collapsed actions */}
      <div className="mt-1 flex flex-wrap gap-3 text-xs">
        <button type="button" className={actionLink} onClick={() => setShow(show === "revisions" ? null : "revisions")}>{t("networth.manage_revisions")}</button>
        <button type="button" className={actionLink} onClick={onPrepay}>{t("networth.prepay")}</button>
        {isOrigin && <button type="button" className={actionLink} onClick={() => setShow(show === "anchor" ? null : "anchor")}>{t("networth.re_anchor")}</button>}
        <button type="button" className={actionLink} onClick={() => setShow(show === "schedule" ? null : "schedule")}>
          {show === "schedule" ? t("networth.hide_schedule") : t("networth.show_schedule")}
        </button>
      </div>
      {show === "revisions" && <RevisionsSection liabilityId={liabilityId} part={part} />}
      {show === "anchor" && <AnchorSection liabilityId={liabilityId} part={part} money={money} />}
      {show === "schedule" && <SchedulePreview part={part} money={money} />}
    </div>
  );
}

function AnchorSection({ liabilityId, part, money }: { liabilityId: string; part: PartSchedule; money: (v: string | number) => string }) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const reAnchor = useReAnchor(household.householdId, liabilityId);
  const [form, setForm] = useState({ anchorDate: isoToday(), anchorBalance: "" });
  return (
    <div className="mt-2 rounded border border-border p-2 dark:border-gray-700">
      <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.re_anchor")}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.re_anchor_help")}</p>
      {part.anchorDate && (
        <p className="mt-1 text-xs text-gray-600 dark:text-gray-300">
          {t("networth.current_anchor")}: {formatDate(part.anchorDate, i18n.language)} · <span className="font-mono">{money(part.anchorBalance ?? "0")}</span>
        </p>
      )}
      <div className="mt-1 flex flex-wrap items-end gap-2">
        <div>
          <Label>{t("networth.value_date")}</Label>
          <Input type="date" value={form.anchorDate} onChange={(e) => setForm({ ...form, anchorDate: e.target.value })} />
        </div>
        <div>
          <Label>{t("networth.real_balance")}</Label>
          <Input type="number" step="0.01" value={form.anchorBalance} onChange={(e) => setForm({ ...form, anchorBalance: e.target.value })} />
        </div>
        <Button className="px-2 py-1 text-xs" disabled={!form.anchorBalance || reAnchor.isPending} onClick={async () => { await reAnchor.mutateAsync({ partId: part.partId, ...form }); setForm({ anchorDate: isoToday(), anchorBalance: "" }); }}>{t("networth.re_anchor")}</Button>
      </div>
    </div>
  );
}

function RevisionsSection({ liabilityId, part }: { liabilityId: string; part: PartSchedule }) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const addRevision = useAddRevision(household.householdId, liabilityId);
  const delRevision = useDeleteRevision(household.householdId, liabilityId);
  const [rev, setRev] = useState({ effectiveDate: isoToday(), annualRate: "" });
  return (
    <div className="mt-2 rounded border border-border p-2 dark:border-gray-700">
      <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.rate_revisions")}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.rate_revisions_help")}</p>
      <ul className="text-xs">
        {part.revisions.map((r) => (
          <li key={r.id} className="flex items-center justify-between gap-2 py-0.5">
            <span>{formatDate(r.effectiveDate, i18n.language)} → {r.annualRate}%</span>
            <button type="button" className="cursor-pointer text-red-600 hover:text-red-700" onClick={() => void delRevision.mutate({ partId: part.partId, revisionId: r.id })}>✕</button>
          </li>
        ))}
      </ul>
      <div className="mt-1 flex flex-wrap items-end gap-2">
        <Input type="date" value={rev.effectiveDate} onChange={(e) => setRev({ ...rev, effectiveDate: e.target.value })} />
        <Input type="number" step="0.0001" placeholder={t("networth.annual_rate")} value={rev.annualRate} onChange={(e) => setRev({ ...rev, annualRate: e.target.value })} />
        <Button className="px-2 py-1 text-xs" disabled={!rev.annualRate} onClick={async () => { await addRevision.mutateAsync({ partId: part.partId, ...rev }); setRev({ effectiveDate: isoToday(), annualRate: "" }); }}>{t("networth.add_revision")}</Button>
      </div>
    </div>
  );
}

function SchedulePreview({ part, money }: { part: PartSchedule; money: (v: string | number) => string }) {
  const { t, i18n } = useTranslation();
  const today = isoToday();
  // Payment history: past rows are elapsed, future rows are the projection — all of them, in scroll
  // containers. Rows are read-only; corrections go through prepayment / rate revision / re-anchor.
  const past = part.rows.filter((r) => r.date <= today);
  const future = part.rows.filter((r) => r.date > today);
  return (
    <div className="mt-2 space-y-3">
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.payment_history_hint")}</p>
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.past_elapsed")}</p>
        {past.length === 0 ? (
          <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.no_past_payments")}</p>
        ) : (
          <div className="mt-1 max-h-64 overflow-auto">
            <table className="w-full text-xs">
              <thead className="text-left text-gray-500 dark:text-gray-400">
                <tr><th>{t("networth.value_date")}</th><th className="text-right">{t("networth.interest")}</th><th className="text-right">{t("networth.principal")}</th><th className="text-right">{t("networth.current_balance")}</th></tr>
              </thead>
              <tbody>
                {past.map((r, idx) => (
                  <tr key={idx} className="border-t border-border">
                    <td>{formatDate(r.date, i18n.language)}</td>
                    <td className="text-right font-mono">{money(r.interest)}</td>
                    <td className="text-right font-mono">{money(r.principal)}</td>
                    <td className="text-right font-mono">{money(r.balance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {/* Upcoming projection */}
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.upcoming_projection")}</p>
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.projected_hint")}</p>
        <div className="mt-1 max-h-64 overflow-auto">
          <table className="w-full text-xs">
            <thead className="text-left text-gray-500 dark:text-gray-400">
              <tr><th>{t("networth.value_date")}</th><th className="text-right">{t("networth.interest")}</th><th className="text-right">{t("networth.principal")}</th><th className="text-right">{t("networth.current_balance")}</th></tr>
            </thead>
            <tbody>
              {future.map((r, idx) => (
                <tr key={idx} className="border-t border-border">
                  <td>{formatDate(r.date, i18n.language)}</td>
                  <td className="text-right font-mono">{money(r.interest)}</td>
                  <td className="text-right font-mono">{money(r.principal)}</td>
                  <td className="text-right font-mono">{money(r.balance)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {/* Actual charged payments */}
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.charged_payments")}</p>
        {part.charged.length === 0 ? (
          <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.charged_empty")}</p>
        ) : (
          <div className="mt-1 max-h-64 overflow-auto">
            <table className="w-full text-xs">
              <thead className="text-left text-gray-500 dark:text-gray-400">
                <tr><th>{t("networth.value_date")}</th><th className="text-right">{t("networth.interest")}</th><th className="text-right">{t("networth.principal")}</th><th className="text-right">{t("networth.current_balance")}</th></tr>
              </thead>
              <tbody>
                {part.charged.map((r, idx) => (
                  <tr key={idx} className="border-t border-border">
                    <td>{formatDate(r.date, i18n.language)}</td>
                    <td className="text-right font-mono">{money(r.interest)}</td>
                    <td className="text-right font-mono">{money(r.principal)}</td>
                    <td className="text-right font-mono">{money(r.resultingBalance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function PrepaymentDialog({
  liabilityId, part, money, onClose,
}: {
  liabilityId: string;
  part: PartSchedule;
  money: (v: string | number) => string;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const recordPrepay = useRecordPrepayment(hid, liabilityId);
  const delPrepay = useDeletePrepayment(hid, liabilityId);
  const simulate = useSimulatePrepayment(hid, liabilityId);
  const [form, setForm] = useState<{ prepaymentDate: string; amount: string; mode: PrepaymentMode }>({ prepaymentDate: isoToday(), amount: "", mode: "reduce_term" });
  const [sim, setSim] = useState<SimulationResult | null>(null);

  return (
    <Dialog title={`${t("networth.prepayments")} — ${part.label || t(`networth.method_${part.method}`)}`} onClose={onClose}>
      <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("networth.prepayments_help")}</p>
      <div className="flex flex-wrap items-end gap-2">
        <div>
          <Label>{t("networth.value_date")}</Label>
          <Input type="date" value={form.prepaymentDate} onChange={(e) => setForm({ ...form, prepaymentDate: e.target.value })} />
        </div>
        <div>
          <Label>{t("networth.value_amount")}</Label>
          <Input type="number" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} />
        </div>
        <div>
          <Label>{t("networth.mode_reduce_term")}/{t("networth.mode_reduce_instalment")}</Label>
          <Select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value as PrepaymentMode })}>
            <option value="reduce_term">{t("networth.mode_reduce_term")}</option>
            <option value="reduce_instalment">{t("networth.mode_reduce_instalment")}</option>
          </Select>
        </div>
      </div>
      <div className="mt-2 flex gap-2">
        <Button variant="secondary" disabled={!form.amount || simulate.isPending} onClick={async () => setSim(await simulate.mutateAsync({ partId: part.partId, ...form }))}>{t("networth.simulate")}</Button>
        <Button disabled={!form.amount || recordPrepay.isPending} onClick={async () => { await recordPrepay.mutateAsync({ partId: part.partId, ...form }); onClose(); }}>{t("networth.record_prepayment")}</Button>
      </div>
      {sim && (
        <div className="mt-2 rounded border border-emerald-300 bg-emerald-50 p-2 text-xs text-emerald-900 dark:border-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-200">
          <p>{t("networth.interest_saved")}: <span className="font-mono">{money(sim.interestSaved)}</span></p>
          <p>{t("networth.new_payoff")}: {sim.newPayoffDate ? formatDate(sim.newPayoffDate, i18n.language) : "—"} · {t("networth.new_instalment")}: <span className="font-mono">{money(sim.newInstalment)}</span></p>
        </div>
      )}
      {part.prepayments.length > 0 && (
        <div className="mt-3">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{t("networth.prepayments")}</p>
          <ul className="text-xs">
            {part.prepayments.map((p) => (
              <li key={p.id} className="flex items-center justify-between gap-2 py-0.5">
                <span>{formatDate(p.prepaymentDate, i18n.language)} · {money(p.amount)} · {t(`networth.mode_${p.mode}`)}</span>
                <button type="button" className="cursor-pointer text-red-600 hover:text-red-700" onClick={() => void delPrepay.mutate({ partId: part.partId, prepaymentId: p.id })}>✕</button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Dialog>
  );
}

function PartForm({
  value, onChange, onCancel, onSave,
}: {
  value: EditingPart;
  onChange: (v: EditingPart) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const isFrench = value.method === "french";

  const derived = useMemo(() => computeDerived({
    principal: Number(value.principal) || 0,
    annualRate: Number(value.rate) || 0,
    method: value.method,
    startDate: value.startDate,
    driver: value.driver,
    term: value.term ? Number(value.term) : null,
    endDate: value.endDate || null,
    instalment: value.instalment ? Number(value.instalment) : null,
  }), [value]);

  const money = (n: number | null) => (n == null ? "—" : formatMoney(String(n), household.currency, i18n.language));

  function setMethod(method: AmortizationMethod) {
    const driver = method !== "french" && value.driver === "instalment" ? "term" : value.driver;
    onChange({ ...value, method, driver });
  }

  return (
    <Card>
      <CardBody className="space-y-3">
        <div>
          <Label>{t("networth.part_label")}</Label>
          <Input value={value.label} onChange={(e) => onChange({ ...value, label: e.target.value })} />
        </div>
        <div>
          <Label>{t("networth.method")}</Label>
          <Select value={value.method} onChange={(e) => setMethod(e.target.value as AmortizationMethod)}>
            {METHODS.map((m) => <option key={m} value={m}>{t(`networth.method_${m}`)}</option>)}
          </Select>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t(`networth.method_hint_${value.method}`)}</p>
        </div>
        <div>
          <Label>{t("networth.start_mode")}</Label>
          <Select value={value.startMode} onChange={(e) => onChange({ ...value, startMode: e.target.value as StartMode })}>
            <option value="current_balance">{t("networth.start_mode_current")}</option>
            <option value="origin">{t("networth.start_mode_origin")}</option>
          </Select>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {value.startMode === "origin" ? t("networth.start_mode_origin_help") : t("networth.start_mode_current_help")}
          </p>
        </div>
        <div>
          <Label>{value.startMode === "origin" ? t("networth.original_principal") : t("networth.outstanding_principal")}</Label>
          <Input type="number" step="0.01" value={value.principal} onChange={(e) => onChange({ ...value, principal: e.target.value })} />
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {value.startMode === "origin" ? t("networth.original_principal_help") : t("networth.outstanding_principal_help")}
          </p>
        </div>
        <div>
          <Label>{t("networth.annual_rate")}</Label>
          <Input type="number" step="0.0001" value={value.rate} onChange={(e) => onChange({ ...value, rate: e.target.value })} />
        </div>
        <div>
          <Label>{value.startMode === "origin" ? t("networth.origin_date") : t("networth.start_date")}</Label>
          <Input type="date" value={value.startDate} onChange={(e) => onChange({ ...value, startDate: e.target.value })} />
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {value.startMode === "origin" ? t("networth.origin_date_help") : t("networth.start_date_help")}
          </p>
        </div>

        <div className="rounded border border-border p-2 dark:border-gray-700">
          <div className="flex flex-wrap items-center gap-2">
            <Label className="mb-0">{t("networth.provided_value")}</Label>
            <Select className="w-auto" value={value.driver} onChange={(e) => onChange({ ...value, driver: e.target.value as Driver })}>
              <option value="term">{t("networth.provide_term")}</option>
              <option value="endDate">{t("networth.provide_end_date")}</option>
              {isFrench && <option value="instalment">{t("networth.provide_instalment")}</option>}
            </Select>
          </div>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {t("networth.term_end_help")}{isFrench ? ` ${t("networth.instalment_help")}` : ""}
          </p>
          <div className="mt-2 space-y-2">
            <DriverField label={t("networth.term_months")} editable={value.driver === "term"} editValue={value.term} onEdit={(v) => onChange({ ...value, term: v })} computed={value.driver === "term" ? null : (derived.termMonths != null ? `${derived.termMonths} ${t("networth.per_month")}` : null)} type="number" t={t} />
            <DriverField label={t("networth.end_date")} editable={value.driver === "endDate"} editValue={value.endDate} onEdit={(v) => onChange({ ...value, endDate: v })} computed={value.driver === "endDate" ? null : (derived.endDate ? formatDate(derived.endDate, i18n.language) : null)} type="date" t={t} />
            {isFrench && (
              <DriverField label={t("networth.instalment")} editable={value.driver === "instalment"} editValue={value.instalment} onEdit={(v) => onChange({ ...value, instalment: v })} computed={value.driver === "instalment" ? null : money(derived.instalment)} type="number" t={t} />
            )}
          </div>
        </div>

        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onCancel}>{t("common.cancel")}</Button>
          <Button onClick={onSave} disabled={!value.principal}>{t("common.save")}</Button>
        </div>
      </CardBody>
    </Card>
  );
}

function DriverField({
  label, editable, editValue, onEdit, computed, type, t,
}: {
  label: string;
  editable: boolean;
  editValue: string;
  onEdit: (v: string) => void;
  computed: string | null;
  type: "number" | "date";
  t: (k: string) => string;
}) {
  return (
    <div>
      <Label className="mb-0">{label}</Label>
      {editable ? (
        <Input type={type} step={type === "number" ? "0.01" : undefined} value={editValue} onChange={(e) => onEdit(e.target.value)} />
      ) : (
        <div className="flex h-9 items-center rounded-md border border-dashed border-border px-3 text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
          <span className="font-mono tabular-nums">{computed ?? "—"}</span>
          <span className="ml-2 text-[10px] uppercase tracking-wide">{t("networth.computed")}</span>
        </div>
      )}
    </div>
  );
}
