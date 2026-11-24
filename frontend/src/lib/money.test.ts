import { describe, expect, it } from "vitest";
import {
  formatCompactMoney,
  formatFullPrecisionMoney,
  formatGroupedMoney,
  formatMoney,
  formatPercent,
  formatPrice,
  formatPriceInput,
  formatQuantity,
  formatQuantityFull,
  toDecimal,
} from "./money";

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

  describe("unit cost precision (formatPrice with referencePrice + formatPriceInput)", () => {
    it("never shows more decimals than the reference price above it in EN and ES", () => {
      // Sub-unit costs when current price is ≥ €1 drop to 2 decimals
      expect(formatPrice("0.792399305858", "EUR", "en", "2.46")).toBe("€0.79");
      expect(formatPrice("0.792399305858", "EUR", "es", "2.46")).toMatch(/^0,79\s?€$/);

      // Sub-unit cost capped by a 6-decimal sub-unit price (e.g. ADA) and 7-decimal (KAS)
      expect(formatPrice("0.792399305858", "EUR", "en", "0.135768")).toBe("€0.792399");
      expect(formatPrice("0.792399305858", "EUR", "es", "0.135768")).toMatch(/^0,792399\s?€$/);

      expect(formatPrice("0.087573690621", "EUR", "en", "0.0240653")).toBe("€0.0875737");
      expect(formatPrice("0.087573690621", "EUR", "es", "0.0240653")).toMatch(/^0,0875737\s?€$/);

      // Sub-cent coin keeps full stored precision when the price above has at least as many decimals
      expect(formatPrice("0.00001234", "EUR", "en", "0.00001234")).toBe("€0.00001234");
      expect(formatPrice("0.00001234", "EUR", "es", "0.00001234")).toMatch(/^0,00001234\s?€$/);
      // And caps if the price above has fewer decimals
      expect(formatPrice("0.00001234", "EUR", "en", "0.00002")).toBe("€0.00001");

      // Values >= 1 stay at 2 decimals even when the price above has 6 decimals
      expect(formatPrice("2.46", "EUR", "en", "0.135768")).toBe("€2.46");
      expect(formatPrice("2.46", "EUR", "es", "0.135768")).toMatch(/^2,46\s?€$/);
      expect(formatPrice("59204.14", "EUR", "en", "58614.00")).toBe("€59,204.14");
      expect(formatPrice("59204.14", "EUR", "es", "58614.00")).toMatch(/^59\.204,14\s?€$/);
    });

    it("produces matching plain decimal input strings and full-precision tooltips", () => {
      expect(formatPriceInput("0.792399305858", "0.135768")).toBe("0.792399");
      expect(formatPriceInput("0.792399305858", "2.46")).toBe("0.79");
      expect(formatPriceInput("2.100000000000")).toBe("2.10");
      expect(formatFullPrecisionMoney("0.792399305858", "EUR", "en")).toBe("€0.792399305858");
      expect(formatFullPrecisionMoney("0.792399305858", "EUR", "es")).toMatch(/^0,792399305858\s?€$/);
    });
  });

  describe("formatQuantity and formatQuantityFull", () => {
    it("formats quantities with locale decimal separator, grouping, stripped trailing zeros, and a 6dp cap", () => {
      expect(formatQuantity("1261.99", "en")).toBe("1,261.99");
      expect(formatQuantity("1261.99", "es")).toBe("1.261,99");

      expect(formatQuantity("0.2508968", "en")).toBe("0.250897");
      expect(formatQuantity("0.2508968", "es")).toBe("0,250897");
      expect(formatQuantityFull("0.2508968", "en")).toBe("0.2508968");
      expect(formatQuantityFull("0.2508968", "es")).toBe("0,2508968");

      expect(formatQuantity("11467.1802", "en")).toBe("11,467.1802");
      expect(formatQuantity("11467.1802", "es")).toBe("11.467,1802");

      expect(formatQuantity("16420", "en")).toBe("16,420");
      expect(formatQuantity("16420", "es")).toBe("16.420");
    });
  });
});

describe("formatGroupedMoney", () => {
  const nb = (text: string) => text.replace(/[\u00a0\u202f]/g, " ");

  it("groups thousands on four-digit amounts in Spanish, where plain Intl does not", () => {
    expect(nb(formatMoney("9132.18", "EUR", "es"))).toBe("9132,18 €");
    expect(nb(formatGroupedMoney("9132.18", "EUR", "es"))).toBe("9.132,18 €");
    expect(nb(formatGroupedMoney("12345.67", "EUR", "es"))).toBe("12.345,67 €");
    expect(nb(formatGroupedMoney("-1200", "EUR", "es"))).toBe("-1.200,00 €");
    expect(nb(formatGroupedMoney("999.5", "EUR", "es"))).toBe("999,50 €");
  });

  it("matches English, which already groups four digits", () => {
    expect(formatGroupedMoney("9132.18", "EUR", "en")).toBe("€9,132.18");
    expect(formatGroupedMoney("12345.67", "EUR", "en")).toBe("€12,345.67");
    expect(formatGroupedMoney("9132.18", "EUR", "en")).toBe(formatMoney("9132.18", "EUR", "en"));
  });
});
