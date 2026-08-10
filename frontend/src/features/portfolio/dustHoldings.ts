import Decimal from "decimal.js";
import type { HoldingSummary } from "@/api/portfolio";

/** Below this market value a holding is table noise rather than a position. One unit of the household's
 *  base currency — not an FX-converted euro. */
export const DUST_HOLDING_MAX_VALUE_EUR = 1;

/** True for rows the Portfolio table hides by default: fully closed positions (the backend reports
 *  exactly 0 once net quantity is zero) and rounding remnants alike. The criterion is money, never
 *  quantity — an 8e-12 remnant is dust, but 8e-12 of an expensive asset can be real money. An unpriced
 *  open holding is never dust: its value is unknown, not small. */
export function isDustHolding(row: HoldingSummary): boolean {
  if (row.currentValue == null) return false;
  try {
    return new Decimal(row.currentValue).abs().lt(DUST_HOLDING_MAX_VALUE_EUR);
  } catch {
    return false;
  }
}

/** Splits rows into the ones the table shows by default and a count of what that hides, in one pass so
 *  the header can disclose the hidden rows without re-deriving them. */
export function partitionDust(rows: HoldingSummary[]): { visible: HoldingSummary[]; hiddenCount: number } {
  const visible = rows.filter((r) => !isDustHolding(r));
  return { visible, hiddenCount: rows.length - visible.length };
}
