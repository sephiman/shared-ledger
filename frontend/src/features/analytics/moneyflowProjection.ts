import type { MoneyFlowProjectionResponse, ProjectedFlowComponents } from "@/api/analytics";
import { monthName } from "@/lib/dates";
import type { MoneyFlowScopeState, SankeyNodeDatum } from "./moneyflow";

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
  const byKey = new Map(components.map((c) => [`${c.side}:${c.id}`, c] as const));
  return (node) => {
    if (node.side !== "income" && node.side !== "expense") return null;
    return byKey.get(`${node.side}:${node.id}`) ?? null;
  };
}

export function hasEstimatedPart(c: ProjectedFlowComponents): boolean {
  return Number(c.recurringDue) !== 0 || Number(c.discretionaryEstimate) !== 0;
}
