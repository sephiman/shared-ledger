import type { MovementStatus, PendingMovement } from "@/api/banks";

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

/**
 * Apply the client-side pending filters: free-text search, categorisation state, and the independent
 * possible-duplicate toggle. [categoryCodeOf] resolves a movement's effective category (including any
 * unsaved per-row edits) so the state filter matches what the user sees.
 */
export function filterPendingMovements(
  movements: PendingMovement[],
  opts: { search: string; categorisationState: CategorisationState; duplicatesOnly: boolean },
  categoryCodeOf: (m: PendingMovement) => string,
): PendingMovement[] {
  const q = opts.search.trim().toLowerCase();
  return movements.filter((m) => {
    if (q && ![m.counterparty, m.description, m.reference].some((v) => (v ?? "").toLowerCase().includes(q))) {
      return false;
    }
    if (opts.categorisationState !== "all") {
      const categorized = categoryCodeOf(m).trim() !== "";
      if (opts.categorisationState === "categorized" && !categorized) return false;
      if (opts.categorisationState === "uncategorized" && categorized) return false;
    }
    return !(opts.duplicatesOnly && !m.possibleDuplicate);

  });
}
