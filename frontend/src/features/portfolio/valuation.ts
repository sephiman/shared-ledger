import Decimal from "decimal.js";

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

/** numerator/denominator as a percent number, or null when undefined (zero/invalid denominator). */
export function percentOf(numerator: string | null | undefined, denominator: string | null | undefined): number | null {
  if (numerator == null || denominator == null) return null;
  try {
    const d = new Decimal(denominator);
    if (d.isZero()) return null;
    return new Decimal(numerator).div(d).times(100).toNumber();
  } catch {
    return null;
  }
}
