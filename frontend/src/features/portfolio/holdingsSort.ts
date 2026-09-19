import Decimal from "decimal.js";
import type { HoldingSummary } from "@/api/portfolio";

export type HoldingSortKey =
  | "symbol"
  | "quantity"
  | "costBasis"
  | "currentPrice"
  | "currentValue"
  | "unrealizedPnl"
  | "realizedPnl"
  | "weight";

export type SortDirection = "asc" | "desc";

export interface HoldingSort {
  key: HoldingSortKey;
  direction: SortDirection;
}

type NumericSortKey = Exclude<HoldingSortKey, "symbol">;

/** What each numeric column sorts on: the very figure it prints, so the order matches what is read. */
const COLUMN_VALUE: Record<NumericSortKey, (row: HoldingSummary, weights: Map<string, string>) => string | null> = {
  quantity: (row) => row.holding.netQuantity,
  costBasis: (row) => row.holding.remainingCostBasis,
  currentPrice: (row) => row.currentPrice,
  currentValue: (row) => row.currentValue,
  unrealizedPnl: (row) => row.unrealizedPnl,
  realizedPnl: (row) => row.realizedPnl,
  weight: (row, weights) => weights.get(row.holding.id) ?? null,
};

/** Text opens A→Z; a money column opens on the biggest position, which is what a click on it is asking for. */
export function defaultDirection(key: HoldingSortKey): SortDirection {
  return key === "symbol" ? "asc" : "desc";
}

/** A header click: a new column starts at its natural direction, the one already sorted flips. */
export function nextSort(current: HoldingSort | null, key: HoldingSortKey): HoldingSort {
  if (current?.key !== key) return { key, direction: defaultDirection(key) };
  return { key, direction: current.direction === "asc" ? "desc" : "asc" };
}

function toDecimal(value: string | null): Decimal | null {
  if (value == null || value === "") return null;
  try {
    return new Decimal(value);
  } catch {
    return null;
  }
}

/** [rows] in the order the current sort asks for, and untouched when nothing is sorted. An unknown value (an
 *  unpriced holding) sinks to the bottom in both directions — no price is not a small price — and ties fall
 *  back to the symbol, so two equal rows never swap places between renders. */
export function sortHoldings(
  rows: HoldingSummary[],
  sort: HoldingSort | null,
  weights: Map<string, string>,
  locale: string,
): HoldingSummary[] {
  if (sort == null) return rows;
  const sign = sort.direction === "asc" ? 1 : -1;
  const bySymbol = (a: HoldingSummary, b: HoldingSummary) =>
    a.holding.symbol.localeCompare(b.holding.symbol, locale);
  if (sort.key === "symbol") return [...rows].sort((a, b) => sign * bySymbol(a, b));

  const valueOf = COLUMN_VALUE[sort.key];
  return [...rows].sort((a, b) => {
    const left = toDecimal(valueOf(a, weights));
    const right = toDecimal(valueOf(b, weights));
    if (left == null || right == null) {
      if (left == null && right == null) return bySymbol(a, b);
      return left == null ? 1 : -1;
    }
    return left.equals(right) ? bySymbol(a, b) : sign * left.comparedTo(right);
  });
}
