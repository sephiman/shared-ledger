import type { AmortizationMethod } from "@/api/amortization";

/** Client-side conversion between a part's driving inputs so the form can show the other two as read-only.
 *  Mirrors the backend AmortizationCalculator's French closed form; the backend recomputes on save. */

export type Driver = "term" | "endDate" | "instalment";

/** Parse a YYYY-MM-DD string into [year, month(1-12), day] without timezone conversion. */
function parseIso(iso: string): [number, number, number] | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso.trim());
  if (!m) return null;
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

/** Whole months from an ISO start date to an ISO end date (never negative). */
export function monthsBetween(startISO: string, endISO: string): number {
  const s = parseIso(startISO);
  const e = parseIso(endISO);
  if (!s || !e) return 0;
  const months = (e[0] - s[0]) * 12 + (e[1] - s[1]);
  return Math.max(0, months);
}

/** ISO date `months` after an ISO start date (day clamped to the month length). */
export function addMonths(startISO: string, months: number): string {
  const s = parseIso(startISO);
  if (!s || !Number.isFinite(months)) return "";
  const total = s[1] - 1 + Math.round(months);
  const year = s[0] + Math.floor(total / 12);
  const month = ((total % 12) + 12) % 12; // 0-11
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const day = Math.min(s[2], daysInMonth);
  return `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function monthlyRate(annualPct: number): number {
  return annualPct / 100 / 12;
}

/** French constant instalment for principal P, annual rate %, over n months. */
export function frenchInstalment(principal: number, annualPct: number, n: number): number | null {
  if (!(principal > 0) || !(n > 0)) return null;
  const i = monthlyRate(annualPct);
  if (i === 0) return principal / n;
  return (principal * i) / (1 - Math.pow(1 + i, -n));
}

/** Months to repay principal P at annual rate % with a constant instalment M (French). */
export function frenchTermFromInstalment(principal: number, annualPct: number, instalment: number): number | null {
  if (!(principal > 0) || !(instalment > 0)) return null;
  const i = monthlyRate(annualPct);
  // Round to the nearest whole month: this term is informational (when the instalment drives the
  // part we send the instalment, not the term), so a cent of instalment rounding shouldn't flip it.
  if (i === 0) return Math.round(principal / instalment);
  // The instalment must at least cover the first month's interest, else it never amortizes.
  if (instalment <= principal * i) return null;
  const n = -Math.log(1 - (principal * i) / instalment) / Math.log(1 + i);
  return Math.round(n);
}

export interface Derived {
  termMonths: number | null;
  endDate: string | null;
  instalment: number | null;
}

/** Given the driving field, compute the other two for display. Instalment-driving is French-only; the
 *  other methods use term ↔ end date. */
export function computeDerived(input: {
  principal: number;
  annualRate: number;
  method: AmortizationMethod;
  startDate: string;
  driver: Driver;
  term?: number | null;
  endDate?: string | null;
  instalment?: number | null;
}): Derived {
  const { principal, annualRate, method, startDate, driver } = input;
  let term: number | null = input.term ?? null;
  let endDate: string | null = input.endDate ?? null;
  let instalment: number | null = input.instalment ?? null;

  if (driver === "endDate" && endDate) {
    term = monthsBetween(startDate, endDate);
  } else if (driver === "term" && term) {
    endDate = addMonths(startDate, term);
  } else if (driver === "instalment" && instalment && method === "french") {
    term = frenchTermFromInstalment(principal, annualRate, instalment);
    endDate = term ? addMonths(startDate, term) : null;
  }

  if (method === "french" && driver !== "instalment" && term) {
    instalment = frenchInstalment(principal, annualRate, term);
  }
  return { termMonths: term, endDate, instalment };
}
