import { describe, expect, it } from "vitest";
import { formatCompactMoney, formatMoney, formatPercent, formatPrice, toDecimal } from "./money";

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

  it("keeps a minimum of 2 decimals for round prices", () => {
    expect(formatPrice("43120.5", "EUR", "en")).toContain("43,120.50");
    expect(formatPrice("1", "EUR", "en")).toContain("1.00");
    expect(formatPrice("0", "EUR", "en")).toContain("0.00");
  });

  it("caps prices of €1 or more at 2 decimals regardless of stored precision", () => {
    // The extended-precision rule is only for sub-€1 values; ≥€1 reads like money.
    expect(formatPrice("1.198734500000", "EUR", "en")).toContain("1.20");
    expect(formatPrice("55567.732403511", "EUR", "en")).toContain("55,567.73");
    expect(formatPrice("-2.5678", "EUR", "en")).toContain("2.57");
  });

  it("shows full precision for tiny prices — no rounding, no significant-figure truncation", () => {
    // Fixed 2dp would collapse this to "0.00"; every stored decimal is preserved.
    expect(formatPrice("0.000002403511", "EUR", "en")).toContain("0.000002403511");
    expect(formatPrice("0.5", "USD", "en")).toContain("0.50");
  });

  it("formats an already-scaled percentage", () => {
    expect(formatPercent(12.34, "en")).toBe("12.3 %");
    expect(formatPercent(0, "en", 0)).toBe("0 %");
  });
});
