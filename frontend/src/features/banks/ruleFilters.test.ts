import { describe, expect, it } from "vitest";
import type { CategorizationRule } from "@/api/banks";
import {
  RULE_FILTER_DEFAULTS,
  filterRules,
  hasActiveRuleFilters,
  sortRules,
} from "./ruleFilters";

function rule(patch: Partial<CategorizationRule>): CategorizationRule {
  return {
    id: "r1",
    matchField: "counterparty",
    matchOp: "equals",
    matchValue: "Albert Heijn",
    categoryCode: "groceries",
    direction: "expense",
    priority: 200,
    source: "learned",
    createdAt: "2026-01-01T00:00:00Z",
    ...patch,
  };
}

const albert = rule({ id: "a", matchValue: "Albert Heijn", categoryCode: "groceries", source: "learned", createdAt: "2026-03-01T00:00:00Z" });
const payroll = rule({ id: "b", matchValue: "Employer", categoryCode: "salary", direction: "income", source: "manual", createdAt: "2026-01-01T00:00:00Z" });
const coffee = rule({ id: "c", matchField: "description", matchOp: "contains", matchValue: "coffee", categoryCode: "eating_out", source: "manual", createdAt: "2026-02-01T00:00:00Z" });
const all = [albert, payroll, coffee];

describe("hasActiveRuleFilters", () => {
  it("is false for the defaults and whitespace-only search", () => {
    expect(hasActiveRuleFilters(RULE_FILTER_DEFAULTS)).toBe(false);
    expect(hasActiveRuleFilters({ ...RULE_FILTER_DEFAULTS, search: "  " })).toBe(false);
  });

  it.each([
    ["search", { search: "albert" }],
    ["field", { field: "description" as const }],
    ["direction", { direction: "income" as const }],
    ["categoryCode", { categoryCode: "groceries" }],
    ["source", { source: "learned" as const }],
  ])("is true when %s differs from default", (_label, patch) => {
    expect(hasActiveRuleFilters({ ...RULE_FILTER_DEFAULTS, ...patch })).toBe(true);
  });
});

describe("filterRules", () => {
  it("returns everything for the defaults", () => {
    expect(filterRules(all, RULE_FILTER_DEFAULTS)).toEqual(all);
  });

  it("searches the rule value case-insensitively", () => {
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, search: "albert" })).toEqual([albert]);
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, search: "COFFEE" })).toEqual([coffee]);
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, search: "nomatch" })).toEqual([]);
  });

  it("filters by match field, direction, category and origin", () => {
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, field: "description" })).toEqual([coffee]);
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, direction: "income" })).toEqual([payroll]);
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, categoryCode: "salary" })).toEqual([payroll]);
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, source: "learned" })).toEqual([albert]);
  });

  it("combines filters", () => {
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, direction: "expense", source: "manual" })).toEqual([coffee]);
    expect(filterRules(all, { ...RULE_FILTER_DEFAULTS, search: "albert", source: "manual" })).toEqual([]);
  });
});

describe("sortRules", () => {
  const label = (code: string) => ({ groceries: "Groceries", salary: "Salary", eating_out: "Eating out" })[code] ?? code;

  it("sorts by creation date in both directions", () => {
    expect(sortRules(all, "newest", label).map((r) => r.id)).toEqual(["a", "c", "b"]);
    expect(sortRules(all, "oldest", label).map((r) => r.id)).toEqual(["b", "c", "a"]);
  });

  it("sorts alphabetically by value, ignoring case", () => {
    expect(sortRules(all, "value", label).map((r) => r.matchValue)).toEqual(["Albert Heijn", "coffee", "Employer"]);
  });

  it("sorts by localized category label", () => {
    expect(sortRules(all, "category", label).map((r) => r.categoryCode)).toEqual(["eating_out", "groceries", "salary"]);
  });

  it("does not mutate the input", () => {
    const input = [...all];
    sortRules(input, "value", label);
    expect(input.map((r) => r.id)).toEqual(["a", "b", "c"]);
  });
});
