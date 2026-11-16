import { describe, expect, it } from "vitest";
import type { TransactionFilters } from "@/api/transactions";
import {
  hasActiveTransactionFilters,
  normalizeAmountInput,
  transactionFiltersFromSearch,
  withTransactionFilters,
} from "./txFilters";

describe("hasActiveTransactionFilters", () => {
  it("is false when only paging fields are set", () => {
    const f: TransactionFilters = { page: 0, size: 50 };
    expect(hasActiveTransactionFilters(f)).toBe(false);
  });

  it("ignores empty-string values", () => {
    expect(hasActiveTransactionFilters({ from: "", amountFrom: "", amountTo: "", categoryCode: "" })).toBe(false);
  });

  it.each([
    ["from", { from: "2026-01-01" }],
    ["to", { to: "2026-02-01" }],
    ["amountFrom", { amountFrom: "10" }],
    ["amountTo", { amountTo: "50.50" }],
    ["direction", { direction: "income" as const }],
    ["categoryCode", { categoryCode: "category.home.rent" }],
    ["categoryGroup", { categoryGroup: "home" }],
  ])("is true when %s is set", (_label, patch) => {
    expect(hasActiveTransactionFilters({ page: 0, size: 50, ...patch })).toBe(true);
  });
});

describe("normalizeAmountInput", () => {
  it("accepts non-negative numbers with dot or comma", () => {
    expect(normalizeAmountInput("10")).toBe("10");
    expect(normalizeAmountInput("10.50")).toBe("10.50");
    expect(normalizeAmountInput(" 10,25 ")).toBe("10.25");
  });

  it("rejects empty, incomplete, negative, or non-numeric strings", () => {
    expect(normalizeAmountInput("")).toBeUndefined();
    expect(normalizeAmountInput("10.")).toBeUndefined();
    expect(normalizeAmountInput("-5")).toBeUndefined();
    expect(normalizeAmountInput("abc")).toBeUndefined();
  });
});

describe("transactionFiltersFromSearch & withTransactionFilters", () => {
  it("round-trips amountFrom and amountTo while keeping tab and sort", () => {
    const base = new URLSearchParams("tab=confirmed&sort=amount_desc&amountFrom=bad");
    expect(transactionFiltersFromSearch(base).amountFrom).toBeUndefined();

    const updated = withTransactionFilters(base, { amountFrom: "15.50", amountTo: "100" });
    expect(updated.toString()).toBe("tab=confirmed&sort=amount_desc&amountFrom=15.50&amountTo=100");
    expect(transactionFiltersFromSearch(updated)).toMatchObject({ amountFrom: "15.50", amountTo: "100" });
  });
});
