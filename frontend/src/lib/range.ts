import { formatDayMonthYear, isoToday, monthName } from "@/lib/dates";

/** The one preset set every range selector offers. Ordered as rendered. */
export type RangePreset = "3m" | "6m" | "ytd" | "1y" | "2y" | "all" | "custom";

/** Presets that resolve to a fixed number of trailing months. */
type FixedPreset = "3m" | "6m" | "1y" | "2y";

/** Controlled value for the range selector. `from`/`to` are ISO dates, used only when preset is "custom". */
export interface RangeValue {
  preset: RangePreset;
  from: string;
  to: string;
}

export const RANGE_MONTHS: Record<FixedPreset, number> = {
  "3m": 3,
  "6m": 6,
  "1y": 12,
  "2y": 24,
};

function isFixed(preset: RangePreset): preset is FixedPreset {
  return preset in RANGE_MONTHS;
}

export function defaultRange(preset: RangePreset = "1y"): RangeValue {
  return { preset, from: "", to: isoToday() };
}

const pad2 = (n: number) => String(n).padStart(2, "0");

function isoMonthsAgo(months: number): string {
  const d = new Date();
  d.setMonth(d.getMonth() - months);
  // Local calendar date, not UTC — toISOString() would shift the day across midnight in +offset zones.
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** Year-month pair with a 1-based month, so it reads like the ISO string it comes from. */
export interface YearMonth {
  year: number;
  month: number;
}

export function currentYearMonth(): YearMonth {
  const d = new Date();
  return { year: d.getFullYear(), month: d.getMonth() + 1 };
}

/** Parse the leading `YYYY-MM` of an ISO date. Returns null for anything unparseable. */
export function ymFromIso(iso: string): YearMonth | null {
  if (!/^\d{4}-\d{2}/.test(iso)) return null;
  const year = Number(iso.slice(0, 4));
  const month = Number(iso.slice(5, 7));
  if (month < 1 || month > 12) return null;
  return { year, month };
}

export function addMonths(ym: YearMonth, delta: number): YearMonth {
  const m0 = ym.year * 12 + (ym.month - 1) + delta;
  return { year: Math.floor(m0 / 12), month: (m0 % 12) + 1 };
}

/** Whole months from `a` to `b`, counting both ends. Negative when `b` precedes `a`. */
export function monthsInclusive(a: YearMonth, b: YearMonth): number {
  return (b.year - a.year) * 12 + (b.month - a.month) + 1;
}

export function firstDayOfMonth(ym: YearMonth): string {
  return `${ym.year}-${pad2(ym.month)}-01`;
}

export function lastDayOfMonth(ym: YearMonth): string {
  // Day 0 of the next month is the last day of this one; UTC keeps it off the local-midnight boundary.
  const last = new Date(Date.UTC(ym.year, ym.month, 0)).getUTCDate();
  return `${ym.year}-${pad2(ym.month)}-${pad2(last)}`;
}

/** Resolve to day-precision `from`/`to` ISO bounds. `undefined` means unbounded (`from` omitted = all
 *  history, `to` omitted = today). Day-granularity views and the two evolution charts use this. */
export function resolveRange(v: RangeValue): { from?: string; to?: string } {
  if (v.preset === "custom") return { from: v.from || undefined, to: v.to || undefined };
  if (v.preset === "all") return {};
  if (v.preset === "ytd") return { from: `${new Date().getFullYear()}-01-01` };
  return { from: isoMonthsAgo(RANGE_MONTHS[v.preset]) };
}

/** Like {@link resolveRange} but always concrete, for callers that must send both bounds. `earliestIso`
 *  supplies the floor for "All time"; without one it falls back to the start of the current year. */
export function resolveDayBounds(v: RangeValue, earliestIso?: string): { from: string; to: string } {
  const { from, to } = resolveRange(v);
  return {
    from: from ?? earliestIso ?? `${new Date().getFullYear()}-01-01`,
    to: to ?? isoToday(),
  };
}

/** Resolve to whole-month bounds: `from` snaps to the first day of its month, `to` to the last day of
 *  its month. `undefined` means unbounded, left for the server to resolve against real data. */
export function resolveMonthRange(v: RangeValue): { from?: string; to?: string } {
  const now = currentYearMonth();
  if (v.preset === "all") return {};
  if (v.preset === "custom") {
    const from = ymFromIso(v.from);
    const to = ymFromIso(v.to);
    return {
      from: from ? firstDayOfMonth(from) : undefined,
      to: to ? lastDayOfMonth(to) : undefined,
    };
  }
  if (v.preset === "ytd") {
    return { from: `${now.year}-01-01`, to: lastDayOfMonth(now) };
  }
  // N trailing whole months, current month included — matching the month counts the endpoints used before.
  return { from: firstDayOfMonth(addMonths(now, -(RANGE_MONTHS[v.preset] - 1))), to: lastDayOfMonth(now) };
}

/** Query params for a month-bucketed analytics endpoint. Explicit bounds win; `months` is the fallback
 *  the endpoint uses when there are none, which only happens for "All time" — hence "full history". */
export function monthRangeParams(v: RangeValue): { months: number; from?: string; to?: string } {
  return { months: 9999, ...resolveMonthRange(v) };
}

/** The whole-month span a custom range snaps to, for the hint under the date inputs. Null unless the
 *  range is a custom one with two valid, correctly ordered dates. */
export function snappedMonthSpan(v: RangeValue): { from: YearMonth; to: YearMonth; months: number } | null {
  if (v.preset !== "custom") return null;
  const from = ymFromIso(v.from);
  const to = ymFromIso(v.to);
  if (!from || !to) return null;
  const months = monthsInclusive(from, to);
  if (months < 1) return null;
  return { from, to, months };
}

/** "Mar 2025 – Feb 2026", collapsing to a single month when both ends match. Named month before year —
 *  {@link formatDayMonthYear} minus the day, so the two read as one format. */
export function formatMonthSpan(from: YearMonth, to: YearMonth, locale: string): string {
  const head = `${monthName(from.month, locale, "short")} ${from.year}`;
  if (from.year === to.year && from.month === to.month) return head;
  return `${head} – ${monthName(to.month, locale, "short")} ${to.year}`;
}

/** A custom range is only usable once both ends are set and ordered; presets are always usable.
 *  Views gate their queries on this so a half-typed date never reaches the API. */
export function isRangeComplete(v: RangeValue): boolean {
  if (v.preset !== "custom") return true;
  return Boolean(v.from) && Boolean(v.to) && v.from <= v.to;
}

type Translate = (key: string, options?: Record<string, unknown>) => string;

/** Human label for the active range, for prose that refers back to it ("vs your average day (…)"). */
export function rangeLabel(v: RangeValue, t: Translate, locale: string): string {
  if (v.preset === "all") return t("range.all");
  if (v.preset === "ytd") return t("range.ytd");
  if (isFixed(v.preset)) {
    const months = RANGE_MONTHS[v.preset];
    return months % 12 === 0
      ? t("range.year", { count: months / 12 })
      : t("range.months", { count: months });
  }
  if (!v.from || !v.to) return t("range.custom");
  return `${formatDayMonthYear(v.from, locale)} – ${formatDayMonthYear(v.to, locale)}`;
}
