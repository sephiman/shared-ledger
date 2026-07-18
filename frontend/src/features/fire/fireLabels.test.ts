import { describe, expect, it } from "vitest";
import { formatHitProbability, hitMedianYear } from "./fireLabels";

describe("formatHitProbability", () => {
  it("shows 0% only when nobody reaches the target", () => {
    expect(formatHitProbability(0)).toBe("0%");
  });

  it("shows <1% for a positive probability that rounds below 1%", () => {
    expect(formatHitProbability(0.004)).toBe("<1%"); // 0.4%
    expect(formatHitProbability(0.0001)).toBe("<1%"); // 0.01%
  });

  it("rounds to an integer percent from 0.5% up", () => {
    expect(formatHitProbability(0.005)).toBe("1%"); // 0.5% rounds up
    expect(formatHitProbability(0.006)).toBe("1%");
    expect(formatHitProbability(0.5)).toBe("50%");
    expect(formatHitProbability(1)).toBe("100%");
  });
});

describe("hitMedianYear", () => {
  it("hides the year when nobody reaches the target", () => {
    expect(hitMedianYear(0, 2050)).toBe("—");
    expect(hitMedianYear(0, null)).toBe("—");
  });

  it("shows the year when some paths reach it, even below 1%", () => {
    expect(hitMedianYear(0.004, 2050)).toBe(2050);
    expect(hitMedianYear(0.9, 2035)).toBe(2035);
  });

  it("shows a dash when there is no median despite a positive probability", () => {
    expect(hitMedianYear(0.5, null)).toBe("—");
  });
});
