import { describe, expect, it } from "vitest";
import { fractionToPercent, percentOf, pnlTone, signedMoney } from "./valuation";

describe("fractionToPercent", () => {
  it("converts backend fractions to percent numbers", () => {
    expect(fractionToPercent("0.1663")).toBeCloseTo(16.63);
    expect(fractionToPercent("-0.05")).toBeCloseTo(-5);
    expect(fractionToPercent("0")).toBe(0);
  });

  it("returns null for missing or malformed values", () => {
    expect(fractionToPercent(null)).toBeNull();
    expect(fractionToPercent(undefined)).toBeNull();
    expect(fractionToPercent("")).toBeNull();
    expect(fractionToPercent("abc")).toBeNull();
  });
});

describe("pnlTone", () => {
  it("classifies gains, losses and zero", () => {
    expect(pnlTone("123.45")).toBe("positive");
    expect(pnlTone("-0.01")).toBe("negative");
    expect(pnlTone("0")).toBe("neutral");
    expect(pnlTone(null)).toBe("neutral");
  });
});

describe("percentOf", () => {
  it("computes the percentage of a base", () => {
    expect(percentOf("400", "500")).toBeCloseTo(80);
    expect(percentOf("-50", "200")).toBeCloseTo(-25);
  });

  it("returns null for zero or missing denominators", () => {
    expect(percentOf("100", "0")).toBeNull();
    expect(percentOf("100", null)).toBeNull();
    expect(percentOf(null, "100")).toBeNull();
  });
});

describe("signedMoney", () => {
  it("prefixes gains with a plus and leaves losses alone", () => {
    expect(signedMoney("10", "€10.00")).toBe("+€10.00");
    expect(signedMoney("-10", "-€10.00")).toBe("-€10.00");
    expect(signedMoney("0", "€0.00")).toBe("€0.00");
  });
});
