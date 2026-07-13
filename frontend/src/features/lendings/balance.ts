import Decimal from "decimal.js";
import type {CompoundingPeriod, InterestType, LendingStatus} from "../../api/lendings";

// TypeScript port of the backend LendingBalanceCalculator. Kept in parity via a shared
// fixture (see balance.test.ts). Used only for the live payment-split preview; the
// backend remains canonical on save.

const DAYS_PER_YEAR = new Decimal(365);
const ONE_HUNDRED = new Decimal(100);
const ZERO = new Decimal(0);

export interface CalcLending {
  principalAmount: string;
  startDate: string;
  interestType: InterestType;
  annualInterestRate: string | null;
  compoundingPeriod: CompoundingPeriod | null;
  status: LendingStatus;
  closedDate: string | null;
}

export interface CalcPayment {
  id: string;
  paymentDate: string;
  amount: string;
  deleted?: boolean;
}

export interface Allocation {
  paymentId: string;
  paymentDate: string;
  amount: Decimal;
  interestPaid: Decimal;
  principalPaid: Decimal;
}

export interface OutstandingResult {
  principalRemaining: Decimal;
  accruedInterest: Decimal;
  totalOutstanding: Decimal;
  allocations: Allocation[];
}

function parseDate(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function daysBetween(a: Date, b: Date): number {
  return Math.round((b.getTime() - a.getTime()) / 86_400_000);
}

function isAfter(a: Date, b: Date): boolean {
  return a.getTime() > b.getTime();
}

function isBefore(a: Date, b: Date): boolean {
  return a.getTime() < b.getTime();
}

function round2(d: Decimal): Decimal {
  return d.toDecimalPlaces(2, Decimal.ROUND_HALF_EVEN);
}

function addMonths(d: Date, months: number): Date {
  const year = d.getUTCFullYear();
  const month = d.getUTCMonth();
  const day = d.getUTCDate();
  const target = new Date(Date.UTC(year, month + months, 1));
  const lastDay = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
  target.setUTCDate(Math.min(day, lastDay));
  return target;
}

function effectiveEnd(lending: CalcLending, asOf: Date): Date {
  if (lending.closedDate) {
    const closed = parseDate(lending.closedDate);
    if (isBefore(closed, asOf)) return closed;
  }
  return asOf;
}

type Event =
  | { kind: "capitalize"; date: Date }
  | { kind: "payment"; date: Date; payment: CalcPayment }
  | { kind: "terminator"; date: Date };

function eventOrder(e: Event): number {
  return e.kind === "capitalize" ? 0 : e.kind === "payment" ? 1 : 2;
}

export function computeOutstanding(lending: CalcLending, payments: CalcPayment[], asOfDate: string): OutstandingResult {
  const start = parseDate(lending.startDate);
  const end = effectiveEnd(lending, parseDate(asOfDate));

  const live = payments
    .filter((p) => !p.deleted)
    .filter((p) => !isBefore(parseDate(p.paymentDate), start))
    .filter((p) => !isAfter(parseDate(p.paymentDate), end))
    .sort((a, b) => {
      const da = parseDate(a.paymentDate).getTime();
      const db = parseDate(b.paymentDate).getTime();
      if (da !== db) return da - db;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });

  if (lending.interestType === "none") {
    return computeNoInterest(lending, live);
  }

  const rate = lending.annualInterestRate ? new Decimal(lending.annualInterestRate).div(ONE_HUNDRED) : ZERO;

  const events: Event[] = [];
  if (lending.interestType === "compound") {
    const period: CompoundingPeriod = lending.compoundingPeriod ?? "monthly";
    let anchor = nextCap(start, period);
    while (!isAfter(anchor, end)) {
      events.push({ kind: "capitalize", date: anchor });
      anchor = nextCap(anchor, period);
    }
  }
  for (const p of live) events.push({ kind: "payment", date: parseDate(p.paymentDate), payment: p });
  events.push({ kind: "terminator", date: end });
  events.sort((a, b) => {
    const t = a.date.getTime() - b.date.getTime();
    return t !== 0 ? t : eventOrder(a) - eventOrder(b);
  });

  let principal = new Decimal(lending.principalAmount);
  let accrued = ZERO;
  let cursor = start;
  const allocations: Allocation[] = [];

  for (const ev of events) {
    if (isAfter(ev.date, cursor)) {
      const days = daysBetween(cursor, ev.date);
      if (days > 0 && rate.gt(ZERO)) {
        accrued = accrued.plus(principal.times(rate).times(days).div(DAYS_PER_YEAR));
      }
      cursor = ev.date;
    }
    if (ev.kind === "capitalize") {
      principal = principal.plus(accrued);
      accrued = ZERO;
    } else if (ev.kind === "payment") {
      const amount = new Decimal(ev.payment.amount);
      const interestPaid = Decimal.max(Decimal.min(amount, round2(accrued)), ZERO);
      const remaining = amount.minus(interestPaid);
      const principalPaid = Decimal.max(Decimal.min(remaining, round2(principal)), ZERO);
      accrued = Decimal.max(accrued.minus(interestPaid), ZERO);
      principal = Decimal.max(principal.minus(principalPaid), ZERO);
      allocations.push({
        paymentId: ev.payment.id,
        paymentDate: ev.payment.paymentDate,
        amount: round2(amount),
        interestPaid: round2(interestPaid),
        principalPaid: round2(principalPaid),
      });
    }
  }

  const principalOut = Decimal.max(round2(principal), ZERO);
  const accruedOut = Decimal.max(round2(accrued), ZERO);
  return {
    principalRemaining: principalOut,
    accruedInterest: accruedOut,
    totalOutstanding: principalOut.plus(accruedOut),
    allocations,
  };
}

function computeNoInterest(lending: CalcLending, payments: CalcPayment[]): OutstandingResult {
  let principal = new Decimal(lending.principalAmount);
  const allocations: Allocation[] = [];
  for (const p of payments) {
    const amount = new Decimal(p.amount);
    const principalPaid = Decimal.max(Decimal.min(amount, principal), ZERO);
    principal = Decimal.max(principal.minus(principalPaid), ZERO);
    allocations.push({
      paymentId: p.id,
      paymentDate: p.paymentDate,
      amount: round2(amount),
      interestPaid: ZERO.toDecimalPlaces(2),
      principalPaid: round2(principalPaid),
    });
  }
  const principalOut = round2(principal);
  return {
    principalRemaining: principalOut,
    accruedInterest: ZERO.toDecimalPlaces(2),
    totalOutstanding: principalOut,
    allocations,
  };
}

function nextCap(date: Date, period: CompoundingPeriod): Date {
  return period === "monthly" ? addMonths(date, 1) : addMonths(date, 12);
}

// Max UUID so the proposed payment sorts LAST among same-date payments (matching the backend, which
// applies payments in creation order — a new payment is the newest). A min id would sort it first
// and show a split that differs from what actually gets persisted.
const SYNTHETIC_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";

export interface SplitPreview {
  interestPaid: Decimal;
  principalPaid: Decimal;
  accruedInterestBefore: Decimal;
  principalBefore: Decimal;
}

/** Live preview: how a proposed payment splits between interest and principal. */
export function previewSplit(
  lending: CalcLending,
  existingPayments: CalcPayment[],
  proposedDate: string,
  proposedAmount: string,
): SplitPreview | null {
  const amount = new Decimal(proposedAmount || "0");
  if (amount.lte(ZERO)) return null;
  const before = computeOutstanding(lending, existingPayments, proposedDate);
  const synthetic: CalcPayment = { id: SYNTHETIC_ID, paymentDate: proposedDate, amount: proposedAmount };
  const after = computeOutstanding(lending, [...existingPayments, synthetic], proposedDate);
  const alloc = after.allocations.find((a) => a.paymentId === SYNTHETIC_ID);
  if (!alloc) return null;
  return {
    interestPaid: alloc.interestPaid,
    principalPaid: alloc.principalPaid,
    accruedInterestBefore: before.accruedInterest,
    principalBefore: before.principalRemaining,
  };
}
