import type { TransactionFilters } from "@/api/transactions";

/** The user-facing list filters (paging fields excluded), so Clear-filters resets exactly these. */
const FILTER_KEYS = ["from", "to", "direction", "categoryCode", "categoryGroup", "isRefund"] as const;

export type TransactionListFilters = Pick<TransactionFilters, (typeof FILTER_KEYS)[number]>;

/** True when any list filter is set — drives showing the "Clear filters" action. */
export function hasActiveTransactionFilters(f: TransactionFilters): boolean {
  return FILTER_KEYS.some((k) => {
    const v = f[k];
    return v !== undefined && v !== null && v !== "";
  });
}

/** The URL is where the list filters live, whether set by hand, by a deep link or by a question. */
export function transactionFiltersFromSearch(params: URLSearchParams): TransactionListFilters {
  const dir = params.get("direction");
  return {
    from: params.get("from") || undefined,
    to: params.get("to") || undefined,
    direction: dir === "income" || dir === "expense" ? dir : undefined,
    categoryCode: params.get("categoryCode") || undefined,
    categoryGroup: params.get("categoryGroup") || undefined,
    isRefund: params.get("isRefund") === "true" ? true : undefined,
  };
}

/** [params] with its list-filter keys replaced by [filters], in a fixed order; other keys (the tab) are kept. */
export function withTransactionFilters(params: URLSearchParams, filters: TransactionListFilters): URLSearchParams {
  const next = new URLSearchParams(params);
  FILTER_KEYS.forEach((k) => next.delete(k));
  for (const k of FILTER_KEYS) {
    const v = filters[k];
    if (v !== undefined && v !== null && v !== "") next.set(k, String(v));
  }
  return next;
}
