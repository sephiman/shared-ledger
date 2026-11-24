import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import type { MoneyFlowMovementClass, MoneyFlowMovements } from "@/api/analytics";
import { InfoTip } from "@/components/ui/InfoTip";
import { Checkbox } from "@/components/ui/primitives";
import { formatGroupedMoney, toDecimal } from "@/lib/money";
import { signedMoney } from "@/features/portfolio/valuation";
import { CONTRIBUTED_NODE_ID, WITHDRAWN_NODE_ID } from "./moneyflow";
import { CONTRIBUTION_COLOR, FROM_CASH_COLOR, STAYS_IN_CASH_COLOR, WITHDRAWAL_COLOR } from "./palette";

const STORAGE_KEY = "sl.analytics.moneyFlowMovements";
/** The key the option had while it was called "Show investments"; read once so a browser that turned it on stays on. */
const LEGACY_STORAGE_KEY = "sl.analytics.moneyFlowInvestments";

function readStoredPreference(): boolean {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored != null) return stored === "1";
  const legacy = localStorage.getItem(LEGACY_STORAGE_KEY);
  if (legacy == null) return false;
  localStorage.setItem(STORAGE_KEY, legacy);
  localStorage.removeItem(LEGACY_STORAGE_KEY);
  return legacy === "1";
}

/** Per browser, like the other Money flow preferences. Off unless this browser turned it on. */
export function useShowMovements(): [boolean, (next: boolean) => void] {
  const [value, setValue] = useState<boolean>(() => {
    try {
      return readStoredPreference();
    } catch {
      return false;
    }
  });
  const update = useCallback((next: boolean) => {
    setValue(next);
    try {
      localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
    } catch {
      // A browser that refuses storage still gets the toggle; it just won't remember it.
    }
  }, []);
  return [value, update];
}

const FLOWS_PATH = "/networth?tab=flows&flow=movements";

/** What a contribution/withdrawal node stands for: one class ("by category"), or every class of one direction
 *  ("by group"), largest first. */
export interface MovementNodeDetail {
  direction: MoneyFlowMovementClass["direction"];
  amount: string;
  movementCount: number;
  cls: MoneyFlowMovementClass | null;
  classes: MoneyFlowMovementClass[];
}

export function movementNodeDetail(nodeId: string, movements: MoneyFlowMovements): MovementNodeDetail | null {
  const cls = movements.classes.find((c) => c.nodeId === nodeId);
  if (cls) return { direction: cls.direction, amount: cls.amount, movementCount: cls.movementCount, cls, classes: [cls] };
  const direction = nodeId === CONTRIBUTED_NODE_ID ? "contribution" : nodeId === WITHDRAWN_NODE_ID ? "withdrawal" : null;
  if (!direction) return null;
  const classes = movements.classes
    .filter((c) => c.direction === direction)
    .sort((a, b) => toDecimal(b.amount).comparedTo(toDecimal(a.amount)));
  return {
    direction,
    amount: direction === "contribution" ? movements.contributed : movements.withdrawn,
    movementCount: classes.reduce((sum, c) => sum + c.movementCount, 0),
    cls: null,
    classes,
  };
}

/** Wealth → Flows filtered by type and period, and by class only when the node is one class. */
export function flowsLinkFor(detail: MovementNodeDetail, from: string, to: string): string {
  const params = new URLSearchParams({ type: detail.direction, ...(detail.cls && { class: detail.cls.assetClassCode }), from, to });
  return `${FLOWS_PATH}&${params.toString()}`;
}

export function MovementsToggle({ checked, onChange }: { checked: boolean; onChange: (next: boolean) => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-2 text-sm">
      <label className="inline-flex cursor-pointer items-center gap-2 text-gray-700 dark:text-gray-300">
        <Checkbox checked={checked} onChange={(e) => onChange(e.target.checked)} />
        {t("analytics.money_flow_show_movements")}
      </label>
      <InfoTip label={t("analytics.money_flow_movements_info_label")} openOnHover>
        <p>{t("analytics.money_flow_movements_info")}</p>
        <Link to={FLOWS_PATH} className="mt-1 inline-block font-medium text-primary hover:underline">
          {t("analytics.money_flow_go_to_flows")}
        </Link>
      </InfoTip>
    </div>
  );
}

/** The movements behind a class figure: the largest descriptions, then the rest as a count. */
export function ClassMovementList({ cls, currency }: { cls: MoneyFlowMovementClass; currency: string }) {
  const { t, i18n } = useTranslation();
  const money = (v: string) => formatGroupedMoney(v, currency, i18n.language);
  return (
    <ul className="space-y-0.5">
      {cls.descriptions.map((d) => (
        <li key={d.description} className="text-gray-900 dark:text-gray-100">
          <span className="text-gray-600 dark:text-gray-400">{d.description}: </span>
          <span className="font-medium tabular-nums">{money(d.amount)}</span>
          {d.count > 1 && <span className="text-gray-500 dark:text-gray-400"> · {t("analytics.money_flow_movements", { count: d.count })}</span>}
        </li>
      ))}
      {cls.moreDescriptions > 0 && (
        <li className="text-gray-500 dark:text-gray-400">{t("analytics.money_flow_and_more", { count: cls.moreDescriptions })}</li>
      )}
      {cls.undescribedCount > 0 && (
        <li className="text-gray-900 dark:text-gray-100">
          <span className="text-gray-600 dark:text-gray-400">{t("analytics.money_flow_no_description")}: </span>
          <span className="font-medium tabular-nums">{money(cls.undescribedAmount)}</span>
          <span className="text-gray-500 dark:text-gray-400"> · {t("analytics.money_flow_movements", { count: cls.undescribedCount })}</span>
        </li>
      )}
    </ul>
  );
}

/** The classes behind a per-direction node, with amounts, largest first. */
function DirectionClassList({ classes, currency }: { classes: MoneyFlowMovementClass[]; currency: string }) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="space-y-0.5">
      {classes.map((c) => (
        <li key={c.nodeId} className="text-gray-900 dark:text-gray-100">
          <span className="text-gray-600 dark:text-gray-400">{t(`asset.${c.assetClassCode}`)}: </span>
          <span className="font-medium tabular-nums">{formatGroupedMoney(c.amount, currency, i18n.language)}</span>
          <span className="text-gray-500 dark:text-gray-400"> · {t("analytics.money_flow_movements", { count: c.movementCount })}</span>
        </li>
      ))}
    </ul>
  );
}

/** A class node lists its descriptions; a per-direction node lists its classes. */
export function MovementNodeList({ detail, currency }: { detail: MovementNodeDetail; currency: string }) {
  return detail.cls ? <ClassMovementList cls={detail.cls} currency={currency} /> : <DirectionClassList classes={detail.classes} currency={currency} />;
}

/** Pinned under the diagram when a contribution/withdrawal node is clicked or tapped: a chart tooltip cannot hold a link. */
export function MovementDetailCard({
  detail,
  title,
  currency,
  from,
  to,
  onClose,
}: {
  detail: MovementNodeDetail;
  title: string;
  currency: string;
  from: string;
  to: string;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  return (
    <section
      aria-label={title}
      className="rounded-md border border-border-strong bg-surface p-3 text-sm"
      style={{ borderLeftColor: detail.direction === "contribution" ? CONTRIBUTION_COLOR : WITHDRAWAL_COLOR, borderLeftWidth: 4 }}
    >
      <div className="mb-1 flex items-baseline justify-between gap-2">
        <p className="font-medium text-gray-900 dark:text-gray-100">
          {title}{" "}
          <span className="tabular-nums">{formatGroupedMoney(detail.amount, currency, i18n.language)}</span>
          <span className="font-normal text-gray-500 dark:text-gray-400"> · {t("analytics.money_flow_movements", { count: detail.movementCount })}</span>
        </p>
        <button
          type="button"
          onClick={onClose}
          aria-label={t("analytics.money_flow_close_details")}
          className="rounded px-1 text-gray-500 hover:text-gray-800 focus-visible:outline-2 focus-visible:outline-primary dark:text-gray-400 dark:hover:text-gray-100"
        >
          ×
        </button>
      </div>
      <div className="text-xs">
        <MovementNodeList detail={detail} currency={currency} />
      </div>
      <Link to={flowsLinkFor(detail, from, to)} className="mt-2 inline-block text-sm font-medium text-primary hover:underline">
        {t("analytics.money_flow_see_in_flows")}
      </Link>
    </section>
  );
}

/** "Contributed" and "Withdrawn" beside the existing totals: separate figures, never folded into income or expenses.
 *  "Cash" closes the line with the balancing term, so a deficit covered by a withdrawal reads as cash going up. */
export function MovementTotals({ movements, currency }: { movements: MoneyFlowMovements; currency: string }) {
  const { t, i18n } = useTranslation();
  return (
    <>
      <span className="text-gray-600 dark:text-gray-400">
        {t("analytics.money_flow_contributed")}:{" "}
        <span className="font-medium tabular-nums" style={{ color: CONTRIBUTION_COLOR }}>
          {formatGroupedMoney(movements.contributed, currency, i18n.language)}
        </span>
      </span>
      <span className="text-gray-600 dark:text-gray-400">
        {t("analytics.money_flow_withdrawn")}:{" "}
        <span className="font-medium tabular-nums" style={{ color: WITHDRAWAL_COLOR }}>
          {formatGroupedMoney(movements.withdrawn, currency, i18n.language)}
        </span>
      </span>
      <CashResult movements={movements} currency={currency} />
    </>
  );
}

function CashResult({ movements, currency }: { movements: MoneyFlowMovements; currency: string }) {
  const { t, i18n } = useTranslation();
  const result = toDecimal(movements.staysInCash).minus(toDecimal(movements.fromCash)).toFixed(2);
  return (
    <span className="text-gray-600 dark:text-gray-400">
      {t("analytics.money_flow_cash_result")}:{" "}
      <span
        className="font-medium tabular-nums"
        style={{ color: toDecimal(result).isNegative() ? FROM_CASH_COLOR : STAYS_IN_CASH_COLOR }}
      >
        {signedMoney(result, formatGroupedMoney(result, currency, i18n.language))}
      </span>
    </span>
  );
}
