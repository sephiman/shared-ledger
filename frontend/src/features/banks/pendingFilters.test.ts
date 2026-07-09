import { describe, expect, it } from "vitest";
import type { PendingMovement } from "@/api/banks";
import {
  PENDING_FILTER_DEFAULTS,
  filterPendingMovements,
  hasActivePendingFilters,
} from "./pendingFilters";

function mv(overrides: Partial<PendingMovement>): PendingMovement {
  return {
    id: overrides.id ?? "1",
    connectionId: "c1",
    connectionLabel: null,
    aspspName: "Bank",
    accountId: "a1",
    accountName: null,
    bookingDate: "2026-01-01",
    valueDate: null,
    direction: "expense",
    amount: "10.00",
    originalAmount: null,
    originalCurrency: null,
    counterparty: null,
    description: null,
    reference: null,
    status: "pending",
    suggestedCategoryCode: null,
    createdTransactionId: null,
    possibleDuplicate: false,
    ...overrides,
  };
}

// A movement's effective category is its suggestion unless a local edit overrides it.
const categoryOf = (m: PendingMovement) => m.suggestedCategoryCode ?? "";

describe("hasActivePendingFilters", () => {
  it("is false for the defaults", () => {
    expect(hasActivePendingFilters(PENDING_FILTER_DEFAULTS)).toBe(false);
  });

  it("is false when search is only whitespace", () => {
    expect(hasActivePendingFilters({ ...PENDING_FILTER_DEFAULTS, search: "   " })).toBe(false);
  });

  it.each([
    ["status", { status: "rejected" as const }],
    ["connectionId", { connectionId: "c1" }],
    ["search", { search: "acme" }],
    ["groupBy", { groupBy: "category" as const }],
    ["categorisationState", { categorisationState: "uncategorized" as const }],
    ["duplicatesOnly", { duplicatesOnly: true }],
  ])("is true when %s differs from default", (_label, patch) => {
    expect(hasActivePendingFilters({ ...PENDING_FILTER_DEFAULTS, ...patch })).toBe(true);
  });
});

describe("filterPendingMovements", () => {
  const items = [
    mv({ id: "a", counterparty: "ACME Corp", suggestedCategoryCode: "category.home.rent" }),
    mv({ id: "b", description: "Coffee shop", suggestedCategoryCode: null }),
    mv({ id: "c", reference: "INV-42", suggestedCategoryCode: "", possibleDuplicate: true }),
  ];
  const opts = { search: "", categorisationState: "all" as const, duplicatesOnly: false };

  it("returns everything with no filters", () => {
    expect(filterPendingMovements(items, opts, categoryOf).map((m) => m.id)).toEqual(["a", "b", "c"]);
  });

  it("matches search across counterparty, description and reference (case-insensitive)", () => {
    expect(filterPendingMovements(items, { ...opts, search: "acme" }, categoryOf).map((m) => m.id)).toEqual(["a"]);
    expect(filterPendingMovements(items, { ...opts, search: "coffee" }, categoryOf).map((m) => m.id)).toEqual(["b"]);
    expect(filterPendingMovements(items, { ...opts, search: "inv-42" }, categoryOf).map((m) => m.id)).toEqual(["c"]);
  });

  it("filters by categorisation state (blank code counts as uncategorized)", () => {
    expect(filterPendingMovements(items, { ...opts, categorisationState: "categorized" }, categoryOf).map((m) => m.id)).toEqual(["a"]);
    expect(filterPendingMovements(items, { ...opts, categorisationState: "uncategorized" }, categoryOf).map((m) => m.id)).toEqual(["b", "c"]);
  });

  it("filters to possible duplicates only", () => {
    expect(filterPendingMovements(items, { ...opts, duplicatesOnly: true }, categoryOf).map((m) => m.id)).toEqual(["c"]);
  });

  it("combines the duplicate toggle with the categorisation-state filter (orthogonal)", () => {
    // c is a duplicate AND uncategorized; the categorized filter excludes it despite the toggle.
    expect(
      filterPendingMovements(items, { ...opts, duplicatesOnly: true, categorisationState: "categorized" }, categoryOf),
    ).toEqual([]);
    expect(
      filterPendingMovements(items, { ...opts, duplicatesOnly: true, categorisationState: "uncategorized" }, categoryOf).map((m) => m.id),
    ).toEqual(["c"]);
  });

  it("uses the resolver so an unsaved per-row category counts as categorized", () => {
    const localEdits: Record<string, string> = { b: "category.groceries.groceries" };
    const resolver = (m: PendingMovement) => localEdits[m.id] ?? m.suggestedCategoryCode ?? "";
    expect(
      filterPendingMovements(items, { ...opts, categorisationState: "uncategorized" }, resolver).map((m) => m.id),
    ).toEqual(["c"]);
  });
});
