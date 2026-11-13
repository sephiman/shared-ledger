import { beforeEach, describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { FireProjection } from "@/api/fire";
import {
  HEADLINE_TEMPLATE_VERSION,
  buildCoverageClause,
  buildHeadline,
  buildProjectionClause,
} from "./fireHeadline";

function baseProjection(): FireProjection {
  return {
    startYear: 2026,
    startingValue: "200000.00",
    snapshotDate: "2026-11-01",
    settings: {
      targetAmount: "0.00",
      targetYear: 2046,
      monthlyContribution: "2000.00",
      returnScenarios: [
        { name: "Conservative", meanPercent: "4.0", stdDevPercent: "5.0", historical: false },
        { name: "Base", meanPercent: "6.0", stdDevPercent: "12.0", historical: false },
      ],
      qualifyingAssetClasses: ["etfs"],
      expectedInflationPct: "2.0",
      safeWithdrawalRatePct: "4.0",
      fatMultiplier: "1.5",
      contributionMode: "manual",
      indexContribution: true,
      essentialSpendingMode: "derived",
      manualEssentialSpending: "0.00",
      totalSpendingMode: "derived",
      manualTotalSpending: "0.00",
      tierLeanEnabled: true,
      tierFireEnabled: true,
      tierFatEnabled: true,
      tierCustomEnabled: false,
      applyCapitalGainsTax: false,
      indexTaxBrackets: false,
      drawdownYears: 30,
      spendingLookbackMonths: 12,
      spendingAggregation: "mean",
      excludeCurrentMonth: false,
      fallbackGainFractionPct: "50.0",
      taxBrackets: [{ lowerBound: "0.00", ratePct: "19.0" }],
      cashFlowEvents: [],
      referenceTier: "fire",
      referenceScenarioIndex: 1,
    },
    spending: {
      essentialMonthly: "1500.00",
      totalMonthly: "2500.00",
      derivedEssentialMonthly: "1500.00",
      derivedTotalMonthly: "2500.00",
      essentialMode: "derived",
      totalMode: "derived",
      monthsAvailable: 12,
      lookbackMonths: 12,
      aggregation: "mean",
      excludeCurrentMonth: false,
    },
    contributions: {
      manualMonthly: "2000.00",
      savingsMonthly: "2000.00",
      savingsRatePct: 40,
      movementsMonthly: "2000.00",
      mode: "manual",
      activeMonthly: "2000.00",
    },
    gainFraction: { percent: "40.00", source: "movements", uncoveredMonths: 0 },
    actualReturn: null,
    actualReturnUnavailableReason: null,
    historicalScenario: null,
    suggestedScenario: null,
    tiers: [
      {
        key: "fire",
        enabled: true,
        monthlyNetSpending: "2500.00",
        annualNetSpending: "30000.00",
        annualGrossSpending: "30000.00",
        estimatedAnnualTax: "0.00",
        targetToday: "950000.00",
        coveragePercent: 21.05,
        targetCurve: [],
      },
    ],
    scenarios: [
      {
        name: "Conservative",
        meanPercent: "4.0",
        stdDevPercent: "5.0",
        historical: false,
        series: [],
        percentiles: [],
        tierStats: [
          {
            tier: "fire",
            deterministicHitYear: 2044,
            probabilityOfReachingTarget: 0.8,
            estimatedYear: 2045,
            medianYearAmongReaching: 2045,
            coastProbabilityOfReachingTarget: 0.2,
            coastEstimatedYear: null,
            coastMedianYearAmongReaching: null,
            coastTargetToday: "400000.00",
            requiredMonthlyToHitTarget: "2200.00",
            drawdownSurvivalRate: 0.7,
            drawdownPercentiles: [],
          },
        ],
      },
      {
        name: "Base",
        meanPercent: "6.0",
        stdDevPercent: "12.0",
        historical: false,
        series: [],
        percentiles: [],
        tierStats: [
          {
            tier: "fire",
            deterministicHitYear: 2040,
            probabilityOfReachingTarget: 0.99,
            estimatedYear: 2040,
            medianYearAmongReaching: 2040,
            coastProbabilityOfReachingTarget: 0.35,
            coastEstimatedYear: null,
            coastMedianYearAmongReaching: 2044,
            coastTargetToday: "350000.00",
            requiredMonthlyToHitTarget: "1800.00",
            drawdownSurvivalRate: 0.67,
            drawdownPercentiles: [],
          },
        ],
      },
    ],
    historicalWealth: [],
    cumulativeContributions: [],
    monteCarloTrials: 10000,
  };
}

describe("fireHeadline", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("exports template version", () => {
    expect(HEADLINE_TEMPLATE_VERSION).toBe(2);
  });

  it("builds coverage clause in EN and ES", async () => {
    const p = baseProjection();
    expect(buildCoverageClause(p, "en", i18n.t.bind(i18n))).toBe(
      "You have 21 % of what you need to live off your investments.",
    );

    await i18n.changeLanguage("es");
    expect(buildCoverageClause(p, "es", i18n.t.bind(i18n))).toBe(
      "Tienes el 21 % de lo que necesitas para vivir de tus inversiones.",
    );
  });

  it("builds reachable projection clause with the survival phrased as conditional on getting there", () => {
    const p = baseProjection();
    const clause = buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n));
    expect(clause).toBe(
      "Saving €2,000/month with the Base scenario, you get there in 2040 (14 years), with a 99% chance of reaching it. " +
        "If you get there, 67% chance the money lasts 30 years.",
    );
  });

  it("uses the reach template at 51 % odds, where half of all trials have a year", () => {
    const p = baseProjection();
    Object.assign(p.scenarios[1].tierStats[0], { probabilityOfReachingTarget: 0.51, estimatedYear: 2045, medianYearAmongReaching: 2041 });
    const clause = buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n));
    expect(clause).toContain("you get there in 2045 (19 years), with a 51% chance");
    expect(clause).not.toContain("2041");
  });

  it("uses the not-before-horizon template at 49 % odds, even though some trials reach it earlier", () => {
    const p = baseProjection();
    Object.assign(p.scenarios[1].tierStats[0], {
      probabilityOfReachingTarget: 0.49,
      estimatedYear: null,
      medianYearAmongReaching: 2041,
      requiredMonthlyToHitTarget: "2158.40",
    });
    const clause = buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n));
    expect(clause).toBe(
      "Saving €2,000/month with the Base scenario, you will most likely not get there before 2046 (49% chance). " +
        "With €158 more a month you would.",
    );
  });

  it("drops the extra-monthly sentence when the required amount is unknown or already covered", () => {
    const p = baseProjection();
    Object.assign(p.scenarios[1].tierStats[0], { probabilityOfReachingTarget: 0.39, estimatedYear: null });
    p.scenarios[1].tierStats[0].requiredMonthlyToHitTarget = null;
    expect(buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n))).not.toContain("more a month");
    p.scenarios[1].tierStats[0].requiredMonthlyToHitTarget = "1990.00";
    expect(buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n))).not.toContain("more a month");
  });

  it("writes the Spanish no-year headline with grouped whole euros", async () => {
    await i18n.changeLanguage("es");
    const p = baseProjection();
    p.contributions.activeMonthly = "1400.00";
    Object.assign(p.scenarios[1].tierStats[0], {
      probabilityOfReachingTarget: 0.39,
      estimatedYear: null,
      requiredMonthlyToHitTarget: "1558.00",
    });
    expect(buildProjectionClause(p, "EUR", "es", i18n.t.bind(i18n)).replace(/\u00a0/g, " ")).toBe(
      "Ahorrando 1.400 €/mes con el escenario Base, lo más probable es que no llegues antes de 2046 (39% de probabilidad). " +
        "Con 158 € más al mes, sí llegarías.",
    );
  });

  it("builds no-target clause when tier has no target", () => {
    const p = baseProjection();
    p.tiers[0].targetToday = null;
    p.tiers[0].coveragePercent = null;
    expect(buildHeadline(p, "EUR", "en", i18n.t.bind(i18n))).toContain("Set up your spending");
  });
});
