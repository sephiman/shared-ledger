import { describe, expect, it } from "vitest";
import { formatCompactMoney, formatMoney, formatPercent, toDecimal } from "./money";

describe("money", () => {
  it("formats EUR in en locale", () => {
    expect(formatMoney("1234.5", "EUR", "en")).toMatch(/€/);
    expect(formatMoney("1234.5", "EUR", "en")).toContain("1,234.50");
  });

  it("treats empty/null as zero", () => {
    expect(toDecimal(null).toString()).toBe("0");
    expect(toDecimal("").toString()).toBe("0");
  });

  it("formats with 2 decimal places", () => {
    expect(formatMoney("100", "EUR", "en")).toContain("100.00");
  });

  it("abbreviates compact euros with the symbol after the number", () => {
    expect(formatCompactMoney(70_000, "EUR", "en")).toBe("70k€");
    expect(formatCompactMoney(210_000, "EUR", "en")).toBe("210k€");
    expect(formatCompactMoney(500, "EUR", "en")).toBe("500€");
    expect(formatCompactMoney(-140_000, "EUR", "en")).toBe("-140k€");
  });

  it("uses European grouping for millions in the es locale", () => {
    // es decimal separator is a comma: 1.5M -> "1,5M€".
    expect(formatCompactMoney(1_500_000, "EUR", "es")).toBe("1,5M€");
  });

  it("formats an already-scaled percentage", () => {
    expect(formatPercent(12.34, "en")).toBe("12.3 %");
    expect(formatPercent(0, "en", 0)).toBe("0 %");
  });
});
