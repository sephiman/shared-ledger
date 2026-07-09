import type { TransactionFilters } from "@/api/transactions";

/** The user-facing list filters (paging fields excluded), so Clear-filters resets exactly these. */
const FILTER_KEYS = ["from", "to", "direction", "categoryCode", "categoryGroup"] as const;

/** True when any list filter is set — drives showing the "Clear filters" action. */
export function hasActiveTransactionFilters(f: TransactionFilters): boolean {
  return FILTER_KEYS.some((k) => {
    const v = f[k];
    return v !== undefined && v !== null && v !== "";
  });
}
