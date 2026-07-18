import type { MoneyFlowNode, MoneyFlowResponse } from "@/api/analytics";
import { monthBounds, yearBounds } from "@/lib/dates";
import { toDecimal } from "@/lib/money";

/** Nodes below this share of their side's total collapse into an "Others" node per side. */
export const OTHERS_THRESHOLD_SHARE = 0.02;

export type MoneyFlowScope = "month" | "trailing12" | "ytd" | "year" | "custom";

export interface MoneyFlowScopeState {
  scope: MoneyFlowScope;
  year: number;
  month: number;
  customFrom: string;
  customTo: string;
}

const pad2 = (n: number) => String(n).padStart(2, "0");

function isoOf(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/**
 * Resolve the selected scope to inclusive ISO from/to bounds. Scope semantics
 * mirror the recurring-share tab: trailing12 spans the 11 months before the
 * current month through the end of the current month; ytd runs Jan 1 → today.
 * Returns null while a custom range is incomplete or inverted.
 */
export function resolveMoneyFlowRange(state: MoneyFlowScopeState, today: Date): { from: string; to: string } | null {
  switch (state.scope) {
    case "month":
      return monthBounds(state.year, state.month);
    case "year":
      return yearBounds(state.year);
    case "trailing12": {
      const endIndex = today.getFullYear() * 12 + today.getMonth();
      const startIndex = endIndex - 11;
      const start = monthBounds(Math.floor(startIndex / 12), (startIndex % 12) + 1);
      const end = monthBounds(today.getFullYear(), today.getMonth() + 1);
      return { from: start.from, to: end.to };
    }
    case "ytd":
      return { from: `${today.getFullYear()}-01-01`, to: isoOf(today) };
    case "custom":
      if (!state.customFrom || !state.customTo || state.customFrom > state.customTo) return null;
      return { from: state.customFrom, to: state.customTo };
  }
}

export interface SankeyNodeDatum {
  /** Display label, resolved for the active language. */
  name: string;
  id: string;
  side: MoneyFlowNode["side"] | "others";
  /** Horizontal band the node belongs to; drives label anchoring. */
  column: "left" | "center" | "right";
  groupCode: string | null;
  amount: number;
  /** Share of its side's total, 0–100. Null for the hub. */
  share: number | null;
  /** Which total the share is relative to. Null for the hub. */
  shareOf: "income" | "expenses" | null;
  fill: string;
  /** Collapsed small flows, for the "Others" tooltip. */
  members?: { name: string; amount: number; share: number }[];
}

export interface SankeyLinkDatum {
  source: number;
  target: number;
  value: number;
}

export interface MoneyFlowSankeyData {
  nodes: SankeyNodeDatum[];
  links: SankeyLinkDatum[];
}

export interface BuildMoneyFlowOptions {
  labelOf: (node: MoneyFlowNode) => string;
  colorOf: (node: MoneyFlowNode) => string;
  othersLabel: string;
  othersColor: string;
}

interface SideAggregation {
  kept: SankeyNodeDatum[];
  others: SankeyNodeDatum | null;
}

function splitSmallFlows(
  nodes: MoneyFlowNode[],
  sideTotal: number,
  column: "left" | "right",
  shareOf: "income" | "expenses",
  opts: BuildMoneyFlowOptions,
  othersId: string,
): SideAggregation {
  const threshold = sideTotal * OTHERS_THRESHOLD_SHARE;
  const kept: SankeyNodeDatum[] = [];
  const small: SankeyNodeDatum[] = [];
  for (const n of nodes) {
    const amount = Number(n.amount);
    const share = sideTotal > 0 ? (amount / sideTotal) * 100 : 0;
    const datum: SankeyNodeDatum = {
      name: opts.labelOf(n),
      id: n.id,
      side: n.side,
      column,
      groupCode: n.groupCode,
      amount,
      share,
      shareOf,
      fill: opts.colorOf(n),
    };
    if (amount < threshold) small.push(datum);
    else kept.push(datum);
  }
  if (small.length === 0) return { kept, others: null };
  const sum = small.reduce((acc, m) => acc.plus(m.amount), toDecimal(0)).toNumber();
  return {
    kept,
    others: {
      name: opts.othersLabel,
      id: othersId,
      side: "others",
      column,
      groupCode: null,
      amount: sum,
      share: sideTotal > 0 ? (sum / sideTotal) * 100 : 0,
      shareOf,
      fill: opts.othersColor,
      members: small.map((m) => ({ name: m.name, amount: m.amount, share: m.share ?? 0 })),
    },
  };
}

/**
 * Map the backend's semantic nodes/links to Recharts' index-based Sankey data,
 * collapsing sub-threshold flows into an "Others" node per side. The hub,
 * "Saved" and "Deficit" nodes never collapse.
 */
export function buildMoneyFlowSankey(data: MoneyFlowResponse, opts: BuildMoneyFlowOptions): MoneyFlowSankeyData {
  if (data.nodes.length === 0) return { nodes: [], links: [] };

  const income = Number(data.income);
  const expenses = Number(data.expenses);
  const hubNode = data.nodes.find((n) => n.side === "hub");
  const savedNode = data.nodes.find((n) => n.side === "saved");
  const deficitNode = data.nodes.find((n) => n.side === "deficit");

  const incomeSide = splitSmallFlows(
    data.nodes.filter((n) => n.side === "income"),
    income,
    "left",
    "income",
    opts,
    "__others_income__",
  );
  const expenseSide = splitSmallFlows(
    data.nodes.filter((n) => n.side === "expense"),
    expenses,
    "right",
    "expenses",
    opts,
    "__others_expense__",
  );

  const nodes: SankeyNodeDatum[] = [...incomeSide.kept];
  if (incomeSide.others) nodes.push(incomeSide.others);
  if (deficitNode) {
    nodes.push({
      name: opts.labelOf(deficitNode),
      id: deficitNode.id,
      side: "deficit",
      column: "left",
      groupCode: null,
      amount: Number(deficitNode.amount),
      share: expenses > 0 ? (Number(deficitNode.amount) / expenses) * 100 : 0,
      shareOf: "expenses",
      fill: opts.colorOf(deficitNode),
    });
  }
  const hubIndexPlaceholder = nodes.length;
  if (hubNode) {
    nodes.push({
      name: opts.labelOf(hubNode),
      id: hubNode.id,
      side: "hub",
      column: "center",
      groupCode: null,
      amount: Number(hubNode.amount),
      share: null,
      shareOf: null,
      fill: opts.colorOf(hubNode),
    });
  }
  nodes.push(...expenseSide.kept);
  if (expenseSide.others) nodes.push(expenseSide.others);
  if (savedNode) {
    nodes.push({
      name: opts.labelOf(savedNode),
      id: savedNode.id,
      side: "saved",
      column: "right",
      groupCode: null,
      amount: Number(savedNode.amount),
      share: income > 0 ? (Number(savedNode.amount) / income) * 100 : 0,
      shareOf: "income",
      fill: opts.colorOf(savedNode),
    });
  }

  const hubIndex = hubIndexPlaceholder;
  const indexOf = new Map(nodes.map((n, i) => [n.id, i] as const));
  const links: SankeyLinkDatum[] = [];
  for (const n of nodes) {
    if (n.side === "hub") continue;
    if (n.column === "left") links.push({ source: indexOf.get(n.id)!, target: hubIndex, value: n.amount });
    else links.push({ source: hubIndex, target: indexOf.get(n.id)!, value: n.amount });
  }
  return { nodes, links };
}
