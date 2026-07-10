import type { MovementStatus } from "@/api/banks";

export type GroupBy = "none" | "connection" | "category";
export type CategorisationState = "all" | "uncategorized" | "categorized";

/** The full set of pending-inbox filters, so the Clear-filters action has a single source of truth. */
export interface PendingFilterState {
  status: MovementStatus;
  connectionId: string;
  search: string;
  groupBy: GroupBy;
  categorisationState: CategorisationState;
  duplicatesOnly: boolean;
}

export const PENDING_FILTER_DEFAULTS: PendingFilterState = {
  status: "pending",
  connectionId: "",
  search: "",
  groupBy: "none",
  categorisationState: "all",
  duplicatesOnly: false,
};

/** True when any filter differs from its default — drives showing the "Clear filters" action. */
export function hasActivePendingFilters(s: PendingFilterState): boolean {
  return (
    s.status !== PENDING_FILTER_DEFAULTS.status ||
    s.connectionId !== PENDING_FILTER_DEFAULTS.connectionId ||
    s.search.trim() !== "" ||
    s.groupBy !== PENDING_FILTER_DEFAULTS.groupBy ||
    s.categorisationState !== PENDING_FILTER_DEFAULTS.categorisationState ||
    s.duplicatesOnly
  );
}
