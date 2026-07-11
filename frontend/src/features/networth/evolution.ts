import { resolveRange, type RangeValue } from "@/components/ui/RangeSelector";

/** Legend/toggle keys for the non-asset-class series (asset classes use their own `code`). */
export const NET_WORTH_KEY = "netWorth";
export const CONTRIBUTIONS_KEY = "contributions";
export const LIABILITIES_KEY = "liabilities";

/** Structural shape of a snapshot the evolution chart consumes (kept loose so the API type fits). */
export interface SnapshotLike {
  snapshotDate: string;
  totalLiabilities: number | string;
  assets: { assetClassCode: string; value: number | string }[];
}

export interface EvolutionRow {
  date: string;
  netWorth: number;
  contributions: number | null;
  liabilities: number;
  [classKey: string]: number | string | null;
}

/**
 * Keep only snapshots whose date falls within the resolved range bounds (inclusive).
 * Reuses the portfolio chart's {@link resolveRange} so both charts share range semantics.
 * Snapshot dates are ISO (`YYYY-MM-DD`), so lexical comparison matches chronological order.
 */
export function filterSnapshotsByRange<T extends { snapshotDate: string }>(
  snapshots: T[],
  range: RangeValue,
): T[] {
  const { from, to } = resolveRange(range);
  return snapshots.filter((s) => (!from || s.snapshotDate >= from) && (!to || s.snapshotDate <= to));
}

/**
 * Build chart rows from snapshots. Net worth is recomputed from the *visible* asset classes
 * minus liabilities (unless liabilities are hidden), so toggling a series off in the legend
 * lowers/raises the net-worth line accordingly. Every class value is still written to the row
 * (whether hidden or not) so the tooltip can render it.
 */
export function buildEvolutionRows(
  snapshots: SnapshotLike[],
  assetClasses: { code: string }[],
  contributionByDate: Map<string, number>,
  hidden: ReadonlySet<string>,
): EvolutionRow[] {
  return snapshots.map((s) => {
    const row: EvolutionRow = {
      date: s.snapshotDate,
      netWorth: 0,
      contributions: contributionByDate.get(s.snapshotDate) ?? null,
      liabilities: Number(s.totalLiabilities),
    };
    let visibleAssets = 0;
    for (const cls of assetClasses) {
      const v = Number(s.assets.find((a) => a.assetClassCode === cls.code)?.value ?? 0);
      row[cls.code] = v;
      if (!hidden.has(cls.code)) visibleAssets += v;
    }
    const liabilities = hidden.has(LIABILITIES_KEY) ? 0 : Number(s.totalLiabilities);
    row.netWorth = visibleAssets - liabilities;
    return row;
  });
}
