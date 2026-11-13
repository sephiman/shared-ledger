import { describe, expect, it } from "vitest";
import i18n from "@/i18n";
import {
  scenarioDisplayName,
  formatFirePercent,
  formatHitProbability,
  formatMultiplier,
  estimatedYearText,
  isDefaultScenarioSet,
  realEquivalentPct,
  typicalCompoundReturnPct,
} from "./fireLabels";

describe("formatFirePercent", () => {
  it("follows the locale's spacing: 65% in English, 65 % in Spanish", () => {
    expect(formatFirePercent(65, "en")).toBe("65%");
    expect(formatFirePercent(65, "es")).toBe("65 %");
    expect(formatFirePercent(3.5, "en", 1)).toBe("3.5%");
    expect(formatFirePercent(3.5, "es", 1)).toBe("3,5 %");
  });
});

describe("formatHitProbability", () => {
  it("shows 0% only when nobody reaches the target", () => {
    expect(formatHitProbability(0, "en")).toBe("0%");
  });

  it("shows <1% for a positive probability that rounds below 1%", () => {
    expect(formatHitProbability(0.004, "en")).toBe("<1%"); // 0.4%
    expect(formatHitProbability(0.0001, "en")).toBe("<1%"); // 0.01%
    expect(formatHitProbability(0.004, "es")).toBe("<1 %");
  });

  it("rounds to an integer percent from 0.5% up", () => {
    expect(formatHitProbability(0.005, "en")).toBe("1%"); // 0.5% rounds up
    expect(formatHitProbability(0.006, "en")).toBe("1%");
    expect(formatHitProbability(0.5, "en")).toBe("50%");
    expect(formatHitProbability(1, "en")).toBe("100%");
    expect(formatHitProbability(0.65, "es")).toBe("65 %");
  });
});

describe("estimatedYearText", () => {
  it("shows the year when at least half of all trials reach the target", () => {
    expect(estimatedYearText(2041, 2050, i18n.t.bind(i18n))).toBe("2041");
  });

  it("says beyond the horizon when there is no year", async () => {
    await i18n.changeLanguage("en");
    expect(estimatedYearText(null, 2050, i18n.t.bind(i18n))).toBe("Beyond 2050");
    await i18n.changeLanguage("es");
    expect(estimatedYearText(null, 2050, i18n.t.bind(i18n))).toBe("Más allá de 2050");
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

describe("formatMultiplier", () => {
  it("uses the locale's decimal separator and drops trailing zeros", () => {
    expect(formatMultiplier("1.50", "es")).toBe("1,5");
    expect(formatMultiplier("1.50", "en")).toBe("1.5");
    expect(formatMultiplier("2.00", "es")).toBe("2");
    expect(formatMultiplier("1.25", "es")).toBe("1,25");
  });
});

describe("scenarioDisplayName", () => {
  const unnamed = (mean: string) => ({ name: "", meanPercent: mean, stdDevPercent: "10.0", historical: false });
  // Listed out of order: defaults follow the ascending mean, not the position.
  const scenarios = [unnamed("8.5"), unnamed("4.0"), unnamed("6.5"), unnamed("10.0")];

  it("names unnamed scenarios by ascending mean in the viewer's language", async () => {
    await i18n.changeLanguage("en");
    expect(scenarios.map((_, i) => scenarioDisplayName(scenarios, i, i18n.t))).toEqual([
      "Optimistic",
      "Conservative",
      "Base",
      "Scenario 4",
    ]);
    await i18n.changeLanguage("es");
    expect(scenarios.map((_, i) => scenarioDisplayName(scenarios, i, i18n.t))).toEqual([
      "Optimista",
      "Conservador",
      "Base",
      "Escenario 4",
    ]);
  });

  it("keeps a name the household gave, in every language", async () => {
    const renamed = [{ ...scenarios[0], name: " Mi cartera " }, ...scenarios.slice(1)];
    await i18n.changeLanguage("en");
    expect(scenarioDisplayName(renamed, 0, i18n.t)).toBe("Mi cartera");
    expect(scenarioDisplayName(renamed, 2, i18n.t)).toBe("Base");
    await i18n.changeLanguage("es");
    expect(scenarioDisplayName(renamed, 0, i18n.t)).toBe("Mi cartera");
  });
});
