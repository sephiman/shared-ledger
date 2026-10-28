import type { QuestionFilters, QuestionRange, QuestionTarget } from "@/api/questions";
import { isoToday } from "@/lib/dates";
import { defaultRange, resolveMonthRange, type RangeValue } from "@/lib/range";
import { explorerSearch } from "@/features/analytics/explorerUrl";
import { withTransactionFilters } from "@/features/transactions/txFilters";

export const TARGET_PATHS = { explorer: "/analytics/explorer", transactions: "/transactions" } as const;

/** No range is all time; an open end runs to today. */
export function toRangeValue(range: QuestionRange | null | undefined): RangeValue {
  if (!range) return defaultRange("all");
  if (range.preset !== "custom") return defaultRange(range.preset);
  return { preset: "custom", from: range.from ?? "", to: range.to ?? isoToday() };
}

/** The list has dates, not presets: a preset becomes the same whole months Explorer shows, ending today. */
export function transactionDates(range: QuestionRange | null | undefined): { from?: string; to?: string } {
  if (!range) return {};
  if (range.preset === "custom") return { from: range.from ?? undefined, to: range.to ?? undefined };
  return { from: resolveMonthRange(defaultRange(range.preset)).from, to: isoToday() };
}

/** One scope fills the main panel (with its overlay); several fill the stacked panel, which has none. */
function explorerUrl(f: QuestionFilters): string {
  const range = toRangeValue(f.range);
  const state =
    f.scopes.length > 1
      ? { stacked: f.scopes, stackedRange: range }
      : { scope: f.scopes[0], range, prior: f.priorYear };
  return `${TARGET_PATHS.explorer}?${explorerSearch(state)}`;
}

function transactionsUrl(f: QuestionFilters): string {
  const single = f.scopes.length === 1 ? f.scopes[0] : undefined;
  const search = withTransactionFilters(new URLSearchParams(), {
    ...transactionDates(f.range),
    direction: f.direction ?? undefined,
    // The list shows one category or group at a time; with several it shows them all, and the line says so.
    categoryCode: single?.type === "category" ? single.code : undefined,
    categoryGroup: single?.type === "group" ? single.code : undefined,
    isRefund: f.isRefund ? true : undefined,
  }).toString();
  return search ? `${TARGET_PATHS.transactions}?${search}` : TARGET_PATHS.transactions;
}

/** One filter object, either view, through the very URL parameters each view's manual controls write. */
export function filtersUrl(view: QuestionTarget, filters: QuestionFilters): string {
  return view === "explorer" ? explorerUrl(filters) : transactionsUrl(filters);
}
