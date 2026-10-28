import type { TFunction } from "i18next";
import type { Category } from "@/api/catalog";
import type { QuestionAnswer, QuestionFilters, QuestionScope, QuestionTarget } from "@/api/questions";
import { categoryLabel } from "@/lib/categoryLabel";
import { formatDayMonthYear } from "@/lib/dates";
import { formatMonthSpan, rangeLabel, ymFromIso } from "@/lib/range";
import { toRangeValue, transactionDates } from "./askTargets";

function scopeWords(scope: QuestionScope, categories: Category[], t: TFunction): string {
  if (scope.type === "group") return t(`category_group.${scope.code}`);
  const category = categories.find((c) => c.code === scope.code);
  const label = category ? categoryLabel(category, t) : scope.code;
  return category?.group ? `${t(`category_group.${category.group}`)} → ${label}` : label;
}

/** Mid-line, so "All time" reads "all time". */
function lowerFirst(text: string, locale: string): string {
  return text.charAt(0).toLocaleLowerCase(locale) + text.slice(1);
}

function explorerRangeWords(f: QuestionFilters, t: TFunction, locale: string): string {
  const value = toRangeValue(f.range);
  if (value.preset !== "custom") return lowerFirst(rangeLabel(value, t, locale), locale);
  // Explorer counts whole months, so a custom range reads as the months it snaps to.
  const from = ymFromIso(value.from);
  const to = ymFromIso(value.to);
  return from && to ? formatMonthSpan(from, to, locale) : rangeLabel(value, t, locale);
}

/** The dates the list actually applies — a preset after whole-month snapping, not its name. */
function listRangeWords(f: QuestionFilters, t: TFunction, locale: string): string {
  const { from, to } = transactionDates(f.range);
  const fromText = from ? formatDayMonthYear(from, locale) : null;
  const toText = to ? formatDayMonthYear(to, locale) : null;
  if (fromText && toText) return `${fromText} – ${toText}`;
  if (fromText) return t("ask.since", { date: fromText });
  if (toText) return t(f.range?.preset === "all" ? "ask.all_time_until" : "ask.until", { date: toText });
  return lowerFirst(t("range.all"), locale);
}

/** The filters in words, as [view] shows them: "Explorer · Outings → Restaurants · Jun 2025 – Aug 2025 · no prior
 *  year", "Transactions · income · all time until 28 Oct 2026". */
export function describeFilters(
  filters: QuestionFilters,
  view: QuestionTarget,
  categories: Category[],
  t: TFunction,
  locale: string,
): string {
  const scopes = filters.scopes.map((s) => scopeWords(s, categories, t));
  if (view === "explorer") {
    const stacked = scopes.length > 1;
    return [
      t("ask.view_explorer"),
      stacked ? t("ask.stacked", { list: new Intl.ListFormat(locale, { style: "long", type: "conjunction" }).format(scopes) }) : scopes[0],
      explorerRangeWords(filters, t, locale),
      // The stacked panel has no overlay.
      ...(stacked ? [] : [t(filters.priorYear ? "ask.prior_on" : "ask.prior_off")]),
    ].filter(Boolean).join(" · ");
  }
  return [
    t("ask.view_transactions"),
    ...(filters.direction ? [t(filters.direction === "expense" ? "ask.expenses" : "ask.income")] : []),
    ...(scopes.length === 1 ? scopes : []),
    listRangeWords(filters, t, locale),
    ...(filters.isRefund ? [t("ask.refunds")] : []),
  ].join(" · ");
}

/** "member or city", in the member's language. */
export function describeUnsupported(answer: QuestionAnswer, locale: string): string | null {
  if (answer.unsupported.length === 0) return null;
  return new Intl.ListFormat(locale, { style: "long", type: "disjunction" }).format(answer.unsupported.map((u) => u.label));
}
