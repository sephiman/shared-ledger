import { describe, expect, it } from "vitest";
import type { TransactionFilters } from "@/api/transactions";
import { hasActiveTransactionFilters } from "./txFilters";

describe("hasActiveTransactionFilters", () => {
  it("is false when only paging fields are set", () => {
    const f: TransactionFilters = { page: 0, size: 50 };
    expect(hasActiveTransactionFilters(f)).toBe(false);
  });

  it("ignores empty-string values", () => {
    expect(hasActiveTransactionFilters({ from: "", categoryCode: "" })).toBe(false);
  });

  it.each([
    ["from", { from: "2026-01-01" }],
    ["to", { to: "2026-02-01" }],
    ["direction", { direction: "income" as const }],
    ["categoryCode", { categoryCode: "category.home.rent" }],
    ["categoryGroup", { categoryGroup: "home" }],
  ])("is true when %s is set", (_label, patch) => {
    expect(hasActiveTransactionFilters({ page: 0, size: 50, ...patch })).toBe(true);
  });
});
