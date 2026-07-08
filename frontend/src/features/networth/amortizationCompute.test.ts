import { describe, expect, it } from "vitest";
import { addMonths, computeDerived, frenchInstalment, frenchTermFromInstalment, monthsBetween } from "./amortizationCompute";

describe("amortizationCompute", () => {
  it("converts term and end date to each other", () => {
    expect(monthsBetween("2026-01-10", "2026-04-10")).toBe(3);
    expect(addMonths("2026-01-10", 3)).toBe("2026-04-10");
    expect(addMonths("2026-01-31", 1)).toBe("2026-02-28"); // clamped
  });

  it("computes the French instalment and its inverse", () => {
    const m = frenchInstalment(100000, 6, 360)!;
    expect(m).toBeCloseTo(599.55, 1);
    expect(frenchTermFromInstalment(100000, 6, m)).toBe(360);
  });

  it("returns null when the instalment can't amortize (below monthly interest)", () => {
    expect(frenchTermFromInstalment(100000, 6, 100)).toBeNull();
  });

  it("computeDerived: term driver yields end date + instalment (French)", () => {
    const d = computeDerived({ principal: 100000, annualRate: 6, method: "french", startDate: "2026-01-01", driver: "term", term: 360 });
    expect(d.endDate).toBe("2056-01-01"); // 360 months = 30 years
    expect(d.instalment).toBeCloseTo(599.55, 1);
  });

  it("computeDerived: instalment driver yields term (French)", () => {
    const d = computeDerived({ principal: 100000, annualRate: 6, method: "french", startDate: "2026-01-01", driver: "instalment", instalment: 599.55 });
    expect(d.termMonths).toBe(360);
  });

  it("computeDerived: end-date driver yields term for any method", () => {
    const d = computeDerived({ principal: 50000, annualRate: 3, method: "german", startDate: "2026-01-01", driver: "endDate", endDate: "2036-01-01" });
    expect(d.termMonths).toBe(120);
  });
});
