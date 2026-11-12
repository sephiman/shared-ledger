import { MemoryRouter } from "react-router-dom";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "@/i18n";
import type { FireProjection } from "@/api/fire";
import { FireTiersCard } from "./FireTiersCard";
import { FireWhatIfBar, initialWhatIfDraft } from "./FireWhatIfBar";
import { FireScenarioTable } from "./FireScenarioTable";
import { FireHeroStrip } from "./FireHeroStrip";

function makeProjection(overrides?: Partial<FireProjection>): FireProjection {
  return {
    startYear: 2026,
    startingValue: "150000.00",
    snapshotDate: "2020-01-01",
    settings: {
      targetAmount: "0.00",
      targetYear: 2046,
      monthlyContribution: "1000.00",
      returnScenarios: [{ meanPercent: "6.0", stdDevPercent: "12.0", historical: false }],
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
      tierFatEnabled: false,
      tierCustomEnabled: false,
      applyCapitalGainsTax: false,
      indexTaxBrackets: false,
      drawdownYears: 30,
      fallbackGainFractionPct: "50.0",
      taxBrackets: [{ lowerBound: "0.00", ratePct: "19.0" }],
      cashFlowEvents: [],
    },
    spending: {
      essentialMonthly: "1200.00",
      totalMonthly: "2000.00",
      derivedEssentialMonthly: "1200.00",
      derivedTotalMonthly: "2000.00",
      essentialMode: "derived",
      totalMode: "derived",
      monthsAvailable: 12,
    },
    contributions: {
      manualMonthly: "1000.00",
      savingsMonthly: "1500.00",
      savingsRatePct: 37.5,
      movementsMonthly: "1000.00",
      mode: "manual",
      activeMonthly: "1000.00",
    },
    gainFraction: { percent: "40.00", source: "movements", uncoveredMonths: 0 },
    actualReturn: null,
    actualReturnUnavailableReason: null,
    historicalScenario: null,
    suggestedScenario: { meanPercent: "8.0", stdDevPercent: "16.0", historical: false },
    tiers: [
      {
        key: "lean",
        enabled: true,
        monthlyNetSpending: "1200.00",
        annualNetSpending: "14400.00",
        annualGrossSpending: "14400.00",
        estimatedAnnualTax: "0.00",
        targetToday: "360000.00",
        coveragePercent: 41.67,
        targetCurve: [{ year: 2026, value: "360000.00" }],
      },
      {
        key: "fire",
        enabled: true,
        monthlyNetSpending: "2000.00",
        annualNetSpending: "24000.00",
        annualGrossSpending: "24000.00",
        estimatedAnnualTax: "0.00",
        targetToday: "600000.00",
        coveragePercent: 25.0,
        targetCurve: [{ year: 2026, value: "600000.00" }],
      },
    ],
    scenarios: [
      {
        meanPercent: "6.0",
        stdDevPercent: "12.0",
        historical: false,
        series: [{ year: 2026, value: "150000.00" }],
        percentiles: [{ year: 2026, p10: "150000.00", p25: "150000.00", p50: "150000.00", p75: "150000.00", p90: "150000.00" }],
        tierStats: [
          {
            tier: "lean",
            deterministicHitYear: 2033,
            probabilityOfReachingTarget: 0.85,
            medianYearReachingTarget: 2034,
            coastProbabilityOfReachingTarget: 0.65,
            coastMedianYearReachingTarget: 2039,
            coastTargetToday: "166000.00",
            requiredMonthlyToHitTarget: "750.00",
            drawdownSurvivalRate: 0.92,
            drawdownPercentiles: [],
          },
          {
            tier: "fire",
            deterministicHitYear: 2038,
            probabilityOfReachingTarget: 0.72,
            medianYearReachingTarget: 2039,
            coastProbabilityOfReachingTarget: 0.35,
            coastMedianYearReachingTarget: 2044,
            coastTargetToday: "277000.00",
            requiredMonthlyToHitTarget: "1450.00",
            drawdownSurvivalRate: 0.88,
            drawdownPercentiles: [],
          },
        ],
      },
    ],
    historicalWealth: [],
    cumulativeContributions: [],
    monteCarloTrials: 1000,
    ...overrides,
  };
}

describe("FIRE components", () => {
  beforeEach(async () => {
    window.localStorage.clear();
    await i18n.changeLanguage("en");
  });

  it("FireTiersCard renders tiers, Coast FIRE progress, and lets user dismiss stale snapshot warning", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <FireTiersCard projection={makeProjection()} currency="EUR" />
      </MemoryRouter>,
    );

    expect(screen.getByText("Lean FIRE")).toBeInTheDocument();
    expect(screen.getByText("FIRE")).toBeInTheDocument();
    expect(screen.getByText(/days old/)).toBeInTheDocument();

    const closeBtn = screen.getByRole("button", { name: /close/i });
    await user.click(closeBtn);
    expect(screen.queryByText(/days old/)).not.toBeInTheDocument();
  });

  it("FireWhatIfBar shows active preview badge and invokes reset/apply", async () => {
    const user = userEvent.setup();
    const base = makeProjection();
    const onReset = vi.fn();
    const onApply = vi.fn();

    render(
      <FireWhatIfBar
        baseProjection={base}
        draft={{ ...initialWhatIfDraft(base), monthlyContribution: "1500.00" }}
        isActive={true}
        isLoading={false}
        isOwner={true}
        currency="EUR"
        onChange={vi.fn()}
        onReset={onReset}
        onApply={onApply}
      />,
    );

    expect(screen.getByText("Preview active (not saved)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reset" }));
    await user.click(screen.getByRole("button", { name: "Apply to settings" }));
    expect(onReset).toHaveBeenCalledTimes(1);
    expect(onApply).toHaveBeenCalledTimes(1);
  });

  it("FireScenarioTable renders both accumulation/drawdown and Coast FIRE cards", () => {
    render(<FireScenarioTable projection={makeProjection()} currency="EUR" />);

    expect(screen.getByText("Scenario × tier summary")).toBeInTheDocument();
    expect(screen.getByText("Coast FIRE outlook")).toBeInTheDocument();
    expect(screen.getByText("Survival (30y)")).toBeInTheDocument();
    expect(screen.getByText("85%")).toBeInTheDocument();
    expect(screen.getByText("92%")).toBeInTheDocument();
  });

  it("FireHeroStrip displays wealth, savings rate, and projected FIRE year", () => {
    render(<FireHeroStrip projection={makeProjection()} currency="EUR" />);

    expect(screen.getByText("2039")).toBeInTheDocument();
    expect(screen.getByText("37.5 %")).toBeInTheDocument();
  });
});
