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
            medianYearReachingTarget: 2045,
            coastProbabilityOfReachingTarget: 0.2,
            coastMedianYearReachingTarget: null,
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
            medianYearReachingTarget: 2040,
            coastProbabilityOfReachingTarget: 0.35,
            coastMedianYearReachingTarget: 2044,
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
    expect(HEADLINE_TEMPLATE_VERSION).toBe(1);
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

  it("builds reachable projection clause with survival rate", () => {
    const p = baseProjection();
    const clause = buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n));
    expect(clause).toContain("Base");
    expect(clause).toContain("2040 (14 years)");
    expect(clause).toContain("99% chance of reaching it");
    expect(clause).toContain("67% that the money lasts 30 years");
  });

  it("builds beyond-horizon projection clause when median year is null", () => {
    const p = baseProjection();
    p.scenarios[1].tierStats[0].medianYearReachingTarget = null;
    p.scenarios[1].tierStats[0].probabilityOfReachingTarget = 0.2;
    const clause = buildProjectionClause(p, "EUR", "en", i18n.t.bind(i18n));
    expect(clause).toContain("don't reach it before 2046");
    expect(clause).toContain("20% chance within the horizon");
  });

  it("builds no-target clause when tier has no target", () => {
    const p = baseProjection();
    p.tiers[0].targetToday = null;
    p.tiers[0].coveragePercent = null;
    expect(buildHeadline(p, "EUR", "en", i18n.t.bind(i18n))).toContain("Set up your spending");
  });
});
