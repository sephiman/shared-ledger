import { describe, expect, it } from "vitest";
import { formatMoney, toDecimal } from "./money";

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
});
