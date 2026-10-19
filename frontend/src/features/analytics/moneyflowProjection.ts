import Decimal from "decimal.js";
import type { MoneyFlowLink, MoneyFlowNode, MoneyFlowProjectionResponse, ProjectedFlowComponents } from "@/api/analytics";
import { monthName } from "@/lib/dates";
import { toDecimal } from "@/lib/money";
import { nodeKey, type MoneyFlowScopeState, type SankeyNodeDatum } from "./moneyflow";

interface YearMonth {
  year: number;
  month: number;
}

/** Projecting the past means nothing, and ranges multiply assumptions: only the running month is projected. */
export function isCurrentMonthScope(state: Pick<MoneyFlowScopeState, "scope" | "year" | "month">, today: Date): boolean {
  return state.scope === "month" && state.year === today.getFullYear() && state.month === today.getMonth() + 1;
}

/** The complete months the moving average actually covers, ascending; empty with no history. */
export function basisMonths(r: MoneyFlowProjectionResponse): YearMonth[] {
  if (r.basisFromYear == null || r.basisFromMonth == null || r.basisToYear == null || r.basisToMonth == null) return [];
  const out: YearMonth[] = [];
  const end = r.basisToYear * 12 + r.basisToMonth - 1;
  for (let i = r.basisFromYear * 12 + r.basisFromMonth - 1; i <= end; i++) {
    out.push({ year: Math.floor(i / 12), month: (i % 12) + 1 });
  }
  return out;
}

/** "Jul–Sep 2026", or "Nov 2025–Jan 2026" across a year boundary. */
export function compactMonthSpan(months: YearMonth[], locale: string): string {
  if (months.length === 0) return "";
  const first = months[0];
  const last = months[months.length - 1];
  const name = (m: YearMonth) => monthName(m.month, locale, "short");
  if (months.length === 1) return `${name(first)} ${first.year}`;
  if (first.year === last.year) return `${name(first)}–${name(last)} ${last.year}`;
  return `${name(first)} ${first.year}–${name(last)} ${last.year}`;
}

/** "Jul, Aug, Sep 2026", naming the year after each month only when the list crosses one. */
export function monthList(months: YearMonth[], locale: string): string {
  if (months.length === 0) return "";
  const sameYear = months.every((m) => m.year === months[0].year);
  const names = months.map((m) => (sameYear ? monthName(m.month, locale, "short") : `${monthName(m.month, locale, "short")} ${m.year}`));
  return sameYear ? `${names.join(", ")} ${months[0].year}` : names.join(", ");
}

/** The estimate behind a diagram node; null for the hub, Saved, Deficit and the collapsed "Others". */
export function componentsLookup(
  components: ProjectedFlowComponents[],
): (node: SankeyNodeDatum) => ProjectedFlowComponents | null {
  const byKey = new Map(components.map((c) => [nodeKey(c), c] as const));
  return (node) => {
    if (node.side !== "income" && node.side !== "expense") return null;
    return byKey.get(nodeKey(node)) ?? null;
  };
}

export function hasEstimatedPart(c: ProjectedFlowComponents): boolean {
  return Number(c.recurringDue) !== 0 || Number(c.discretionaryEstimate) !== 0;
}

export function estimatedRemainder(c: ProjectedFlowComponents): Decimal {
  return toDecimal(c.recurringDue).plus(c.discretionaryEstimate);
}

/** The disabled keys that name a node of this response; stale ones from a refetch don't count. */
export function presentDisabledKeys(data: MoneyFlowProjectionResponse, disabled: ReadonlySet<string>): Set<string> {
  return new Set(data.components.map(nodeKey).filter((k) => disabled.has(k)));
}

/** The projection with each disabled node cut back to its actual: its side's total loses exactly that node's
 *  recurring-due + discretionary estimate, and Saved, Deficit and the hub follow the backend's flow rules
 *  (saved = max(I − E, 0), deficit = max(E − I, 0), hub = I + deficit). A disabled node keeps its place and is
 *  drawn at max(actual, 0); the totals keep the exact actual even when refunds pushed it negative. */
export function withActualsOnly(data: MoneyFlowProjectionResponse, disabled: ReadonlySet<string>): MoneyFlowProjectionResponse {
  const dropped = new Map(data.components.filter((c) => disabled.has(nodeKey(c))).map((c) => [nodeKey(c), c] as const));
  if (dropped.size === 0) return data;

  const remainderOf = (side: ProjectedFlowComponents["side"]) =>
    [...dropped.values()].filter((c) => c.side === side).reduce((acc, c) => acc.plus(estimatedRemainder(c)), toDecimal(0));
  const income = toDecimal(data.income).minus(remainderOf("income"));
  const expenses = toDecimal(data.expenses).minus(remainderOf("expense"));
  const saved = Decimal.max(income.minus(expenses), 0);
  const deficit = Decimal.max(expenses.minus(income), 0);
  const money = (d: Decimal) => d.toFixed(2);

  const flowNode = (n: MoneyFlowNode): MoneyFlowNode => {
    const c = dropped.get(nodeKey(n));
    return c ? { ...n, amount: money(Decimal.max(toDecimal(c.actual), 0)) } : n;
  };
  const derived = (side: "hub" | "saved" | "deficit", amount: Decimal): MoneyFlowNode => ({
    id: data.nodes.find((n) => n.side === side)?.id ?? side,
    side,
    groupCode: null,
    amount: money(amount),
  });
  const hub = derived("hub", income.plus(deficit));
  const nodes = [
    ...data.nodes.filter((n) => n.side === "income").map(flowNode),
    ...(deficit.isZero() ? [] : [derived("deficit", deficit)]),
    hub,
    ...data.nodes.filter((n) => n.side === "expense").map(flowNode),
    ...(saved.isZero() ? [] : [derived("saved", saved)]),
  ];
  const links: MoneyFlowLink[] = nodes
    .filter((n) => n.side !== "hub")
    .map((n) =>
      n.side === "income" || n.side === "deficit"
        ? { source: n.id, target: hub.id, amount: n.amount }
        : { source: hub.id, target: n.id, amount: n.amount },
    );
  return { ...data, income: money(income), expenses: money(expenses), saved: money(saved), deficit: money(deficit), nodes, links };
}
