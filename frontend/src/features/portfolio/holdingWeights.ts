import Decimal from "decimal.js";
import type { HoldingSummary } from "@/api/portfolio";

/** The scale the backend reports fractions at; matched so a re-based weight rounds the same way. */
const FRACTION_SCALE = 4;

/** Each row's share of the priced value of [rows], keyed by holding id, as a backend-shaped fraction string
 *  ("0.1663" = 16.63 %). Applying the backend's rule to the rows on screen is what makes the column re-base
 *  when the asset-type filter narrows the table, instead of leaving whole-portfolio shares that no longer
 *  add up to 100 %. An unpriced row gets no entry, and neither does anything when the total is zero — a
 *  share of nothing is undefined, not 0 %. */
export function weightsWithin(rows: HoldingSummary[]): Map<string, string> {
  const priced: [string, Decimal][] = [];
  let total = new Decimal(0);
  for (const row of rows) {
    if (row.currentValue == null) continue;
    const value = new Decimal(row.currentValue);
    priced.push([row.holding.id, value]);
    total = total.plus(value);
  }
  if (total.isZero()) return new Map();
  return new Map(
    priced.map(([id, value]) => [
      id,
      value.div(total).toDecimalPlaces(FRACTION_SCALE, Decimal.ROUND_HALF_EVEN).toString(),
    ]),
  );
}
