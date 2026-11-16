export type TransactionSortColumn = "date" | "category" | "description" | "amount";
export type SortDirection = "asc" | "desc";
export type TransactionSortKey = `${TransactionSortColumn}_${SortDirection}`;

export const DEFAULT_TRANSACTION_SORT: TransactionSortKey = "date_desc";

const VALID_SORTS = new Set<string>([
  "date_asc",
  "date_desc",
  "category_asc",
  "category_desc",
  "description_asc",
  "description_desc",
  "amount_asc",
  "amount_desc",
]);

export function parseTransactionSort(raw: string | null): TransactionSortKey {
  return raw && VALID_SORTS.has(raw) ? (raw as TransactionSortKey) : DEFAULT_TRANSACTION_SORT;
}

export function splitTransactionSort(sort: TransactionSortKey): {
  column: TransactionSortColumn;
  direction: SortDirection;
} {
  const [column, direction] = sort.split("_") as [TransactionSortColumn, SortDirection];
  return { column, direction };
}

/** Date and amount open newest/biggest first; text columns open A→Z. */
export function defaultSortDirection(column: TransactionSortColumn): SortDirection {
  return column === "date" || column === "amount" ? "desc" : "asc";
}

export function nextTransactionSort(current: TransactionSortKey, column: TransactionSortColumn): TransactionSortKey {
  const parsed = splitTransactionSort(current);
  if (parsed.column !== column) return `${column}_${defaultSortDirection(column)}`;
  return `${column}_${parsed.direction === "asc" ? "desc" : "asc"}`;
}

/** Stores [sort] in [params] only when it differs from the default, keeping URLs clean. */
export function withTransactionSort(params: URLSearchParams, sort: TransactionSortKey): URLSearchParams {
  const next = new URLSearchParams(params);
  if (sort === DEFAULT_TRANSACTION_SORT) next.delete("sort");
  else next.set("sort", sort);
  return next;
}
