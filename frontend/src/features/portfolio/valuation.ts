import Decimal from "decimal.js";
import { formatNumber } from "@/lib/money";

/** Converts a backend fraction string (e.g. "0.1663") into a percent number (16.63). */
export function fractionToPercent(fraction: string | null | undefined): number | null {
  if (fraction == null || fraction === "") return null;
  try {
    return new Decimal(fraction).times(100).toNumber();
  } catch {
    return null;
  }
}

export type PnlTone = "positive" | "negative" | "neutral";

/** Display tone for a signed money/percent value. */
export function pnlTone(value: string | number | null | undefined): PnlTone {
  if (value == null || value === "") return "neutral";
  try {
    const d = new Decimal(value);
    if (d.isPositive() && !d.isZero()) return "positive";
    if (d.isNegative()) return "negative";
    return "neutral";
  } catch {
    return "neutral";
  }
}

/** Formats a signed amount with an explicit plus for gains. */
export function signedMoney(value: string, formatted: string): string {
  return new Decimal(value).isPositive() && !new Decimal(value).isZero() ? `+${formatted}` : formatted;
}

/**
 * numerator/denominator as a fraction string (the backend convention: "0.1234" = +12.34 %),
 * or null when undefined (null numerator, zero/invalid denominator) — mirrors the backend
 * rule so filtered subtotals behave like the summary fields.
 */
export function fractionOf(numerator: string | null | undefined, denominator: string | null | undefined): string | null {
  if (numerator == null || denominator == null) return null;
  try {
    const d = new Decimal(denominator);
    if (d.isZero()) return null;
    return new Decimal(numerator).div(d).toString();
  } catch {
    return null;
  }
}

/** The base a user chose for the portfolio return percentages; mirrors the backend enum. */
export type ReturnBasis = "OPEN_COST" | "NET_INVESTED" | "TURNOVER";

/**
 * Smallest net-invested base we divide by. At or below this (covers ≤ 0 "house money" plus
 * sub-cent noise) there is no own-money base to show a return over — the percentages go to "—".
 */
export const NET_INVESTED_MIN_BASE = new Decimal("0.01");

/** Euro amounts (backend strings) the return percentages are derived from. */
export interface ReturnAmounts {
  totalUnrealizedPnl: string | null;
  totalRealizedPnl: string;
  totalReturn: string | null;
  /** Cost of the currently-held (open) lots. */
  totalCostBasis: string;
  /** FIFO cost of every sold lot over the whole history. */
  totalSoldCostBasis: string;
}

export interface ReturnPercents {
  unrealizedPnlPct: string | null;
  /** Denominator used for unrealized %, for the "over €X" tooltip. */
  unrealizedBasis: string;
  realizedPnlPct: string | null;
  /** Denominator used for realized %, for the "over €X" tooltip. */
  realizedBasis: string;
  totalReturnPct: string | null;
  /** Denominator used for total return %, for the "over €X" tooltip. */
  totalBasis: string;
  /** NET_INVESTED only: the base is ≤ NET_INVESTED_MIN_BASE, so every percentage is unavailable. */
  houseMoney: boolean;
  /**
   * Net money contributed from outside the portfolio (open-lots cost − realized P&L, so buys
   * funded by prior sales cancel out) — "your own money at risk". Mode-independent: the same
   * value the NET_INVESTED base uses, always present so it can be shown regardless of basis.
   */
  netInvested: string;
  /** True when netInvested ≤ NET_INVESTED_MIN_BASE ("house money"): positions funded by gains. */
  netInvestedHouseMoney: boolean;
}

/**
 * All three portfolio return percentages for the chosen basis, from euro amounts the summary
 * already carries — the euro amounts are identical in every mode, only the denominator changes.
 * This is the single computation path: no consumer derives these independently.
 *
 * - OPEN_COST (default): all three over the open-lots cost, so unrealized/realized/total share
 *   one base and add up like the euros.
 * - NET_INVESTED: all three over the net money contributed from outside (open cost − realized,
 *   so buys funded by prior sales cancel) — "your own money at risk"; also one shared base.
 *   When that base is ≤ NET_INVESTED_MIN_BASE the percentages are unavailable (house money).
 * - TURNOVER: realized over the cost of all sold lots, total over open + sold cost, unrealized
 *   over open cost — the historical behavior, whose denominators grow with sell-and-rebuy churn.
 */
export function returnPercents(basis: ReturnBasis, a: ReturnAmounts): ReturnPercents {
  // Net money contributed from outside the portfolio — buys funded by earlier sales cancel out.
  // Computed once here, so the always-on "Own money" line and the NET_INVESTED base agree.
  let net: Decimal;
  try {
    net = new Decimal(a.totalCostBasis).minus(a.totalRealizedPnl);
  } catch {
    net = new Decimal(0);
  }
  const netInvested = net.toString();
  const netInvestedHouseMoney = net.lt(NET_INVESTED_MIN_BASE);
  const common = { netInvested, netInvestedHouseMoney };

  if (basis === "TURNOVER") {
    let deployed: string;
    try {
      deployed = new Decimal(a.totalCostBasis).plus(a.totalSoldCostBasis).toString();
    } catch {
      deployed = a.totalCostBasis;
    }
    return {
      unrealizedPnlPct: fractionOf(a.totalUnrealizedPnl, a.totalCostBasis),
      unrealizedBasis: a.totalCostBasis,
      realizedPnlPct: fractionOf(a.totalRealizedPnl, a.totalSoldCostBasis),
      realizedBasis: a.totalSoldCostBasis,
      totalReturnPct: fractionOf(a.totalReturn, deployed),
      totalBasis: deployed,
      houseMoney: false,
      ...common,
    };
  }
  if (basis === "NET_INVESTED") {
    if (netInvestedHouseMoney) {
      // House money: more taken out than ever put in — no base to divide by.
      return {
        unrealizedPnlPct: null,
        unrealizedBasis: netInvested,
        realizedPnlPct: null,
        realizedBasis: netInvested,
        totalReturnPct: null,
        totalBasis: netInvested,
        houseMoney: true,
        ...common,
      };
    }
    return {
      unrealizedPnlPct: fractionOf(a.totalUnrealizedPnl, netInvested),
      unrealizedBasis: netInvested,
      realizedPnlPct: fractionOf(a.totalRealizedPnl, netInvested),
      realizedBasis: netInvested,
      totalReturnPct: fractionOf(a.totalReturn, netInvested),
      totalBasis: netInvested,
      houseMoney: false,
      ...common,
    };
  }
  // OPEN_COST (default): everything over the cost of the positions still held.
  return {
    unrealizedPnlPct: fractionOf(a.totalUnrealizedPnl, a.totalCostBasis),
    unrealizedBasis: a.totalCostBasis,
    realizedPnlPct: fractionOf(a.totalRealizedPnl, a.totalCostBasis),
    realizedBasis: a.totalCostBasis,
    totalReturnPct: fractionOf(a.totalReturn, a.totalCostBasis),
    totalBasis: a.totalCostBasis,
    houseMoney: false,
    ...common,
  };
}

/** "16.6%" from a backend fraction string; "—" when undefined. [signed] adds "+" for gains. */
export function percentLabel(fraction: string | null | undefined, locale: string, signed = false): string {
  const pct = fractionToPercent(fraction);
  if (pct == null) return "—";
  const formatted = `${formatNumber(pct, locale, 1)}%`;
  return signed && pct > 0 ? `+${formatted}` : formatted;
}
