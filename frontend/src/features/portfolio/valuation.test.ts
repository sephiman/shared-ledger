import { describe, expect, it } from "vitest";
import { fractionOf, fractionToPercent, percentLabel, pnlTone, signedMoney } from "./valuation";

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

describe("fractionOf", () => {
  it("divides into a backend-convention fraction string", () => {
    expect(fractionOf("5000", "10000")).toBe("0.5");
    // The sell-and-rebuy total return: (5000 + 3000) / (15000 + 10000).
    expect(fractionOf("8000", "25000")).toBe("0.32");
    expect(fractionOf("-50", "200")).toBe("-0.25");
  });

  it("returns null for zero or missing inputs — never a fake 0", () => {
    expect(fractionOf("100", "0")).toBeNull();
    expect(fractionOf("100", null)).toBeNull();
    expect(fractionOf(null, "100")).toBeNull();
    expect(fractionOf("100", "abc")).toBeNull();
  });
});

describe("percentLabel", () => {
  it("formats a backend fraction at one decimal", () => {
    expect(percentLabel("0.5", "en")).toBe("50.0%");
    expect(percentLabel("0.1663", "en")).toBe("16.6%");
  });

  it("adds a plus only when signed and positive", () => {
    expect(percentLabel("0.5", "en", true)).toBe("+50.0%");
    expect(percentLabel("-0.25", "en", true)).toBe("-25.0%");
    expect(percentLabel("0", "en", true)).toBe("0.0%");
  });

  it("renders an em dash when the fraction is undefined", () => {
    expect(percentLabel(null, "en")).toBe("—");
    expect(percentLabel(undefined, "en")).toBe("—");
  });
});

describe("signedMoney", () => {
  it("prefixes gains with a plus and leaves losses alone", () => {
    expect(signedMoney("10", "€10.00")).toBe("+€10.00");
    expect(signedMoney("-10", "-€10.00")).toBe("-€10.00");
    expect(signedMoney("0", "€0.00")).toBe("€0.00");
  });
});
