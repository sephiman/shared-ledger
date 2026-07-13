import type { CategorizationRule, Direction, RuleField, RuleSource } from "@/api/banks";

export type RuleSortKey = "newest" | "oldest" | "value" | "category";

/** The full set of rules-page filters, so the Clear-filters action has a single source of truth. */
export interface RuleFilterState {
  search: string;
  field: RuleField | "all";
  direction: Direction | "all";
  /** Category code; empty string means "all categories". */
  categoryCode: string;
  source: RuleSource | "all";
}

export const RULE_FILTER_DEFAULTS: RuleFilterState = {
  search: "",
  field: "all",
  direction: "all",
  categoryCode: "",
  source: "all",
};

/** True when any filter differs from its default — drives showing the "Clear filters" action. */
export function hasActiveRuleFilters(s: RuleFilterState): boolean {
  return (
    s.search.trim() !== "" ||
    s.field !== RULE_FILTER_DEFAULTS.field ||
    s.direction !== RULE_FILTER_DEFAULTS.direction ||
    s.categoryCode !== RULE_FILTER_DEFAULTS.categoryCode ||
    s.source !== RULE_FILTER_DEFAULTS.source
  );
}

/** All filters combine; the free-text search matches the rule's value case-insensitively. */
export function filterRules(rules: CategorizationRule[], s: RuleFilterState): CategorizationRule[] {
  const q = s.search.trim().toLowerCase();
  return rules.filter(
    (r) =>
      (!q || r.matchValue.toLowerCase().includes(q)) &&
      (s.field === "all" || r.matchField === s.field) &&
      (s.direction === "all" || r.direction === s.direction) &&
      (!s.categoryCode || r.categoryCode === s.categoryCode) &&
      (s.source === "all" || r.source === s.source),
  );
}

/** Category sorting compares localized labels, so the caller supplies the code -> label resolver. */
export function sortRules(
  rules: CategorizationRule[],
  sort: RuleSortKey,
  categoryLabelOf: (code: string) => string,
): CategorizationRule[] {
  const byText = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: "base" });
  const sorted = [...rules];
  switch (sort) {
    case "newest":
      sorted.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
      break;
    case "oldest":
      sorted.sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
      break;
    case "value":
      sorted.sort((a, b) => byText(a.matchValue, b.matchValue));
      break;
    case "category":
      sorted.sort((a, b) => byText(categoryLabelOf(a.categoryCode), categoryLabelOf(b.categoryCode)));
      break;
  }
  return sorted;
}
