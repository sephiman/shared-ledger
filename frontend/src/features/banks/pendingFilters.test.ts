import { describe, expect, it } from "vitest";
import {
  PENDING_FILTER_DEFAULTS,
  hasActivePendingFilters,
} from "./pendingFilters";

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
