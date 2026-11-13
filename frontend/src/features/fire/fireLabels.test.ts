import { describe, expect, it } from "vitest";
import {
  formatHitProbability,
  hitMedianYear,
  isDefaultScenarioSet,
  realEquivalentPct,
  typicalCompoundReturnPct,
} from "./fireLabels";

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

describe("default scenario set & typical compound return", () => {
  it("recognizes only the exact ordered current seed set as default", () => {
    const seeds = [
      { meanPercent: "4.0", stdDevPercent: "5.0", historical: false },
      { meanPercent: "6.5", stdDevPercent: "10.0", historical: false },
      { meanPercent: "8.5", stdDevPercent: "15.0", historical: false },
    ];
    expect(isDefaultScenarioSet(seeds)).toBe(true);
    expect(isDefaultScenarioSet([seeds[1], seeds[0], seeds[2]])).toBe(false);
    expect(isDefaultScenarioSet([seeds[0], { meanPercent: "6.0", stdDevPercent: "12.0" }, { meanPercent: "8.0", stdDevPercent: "18.0" }])).toBe(false);
    expect(isDefaultScenarioSet(seeds.slice(0, 2))).toBe(false);
  });

  it("computes exact exp(mu_ln) - 1 compound growth matching the simulation engine", () => {
    const compound = typicalCompoundReturnPct(6.5, 10.0);
    // Exact exp(mu_ln) - 1 is ~6.03%, distinct from the 6.5 - 10^2/200 = 6.00% approximation
    expect(compound).toBeGreaterThan(6.02);
    expect(compound).toBeLessThan(6.05);
    expect(realEquivalentPct(compound, 2.0)).toBeCloseTo(((1 + compound / 100) / 1.02 - 1) * 100, 6);
  });
});
