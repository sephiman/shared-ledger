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
import { FireChart } from "./FireChart";
import { FireSettingsCard } from "./FireSettingsCard";

function makeProjection(overrides?: Partial<FireProjection>): FireProjection {
  return {
    startYear: 2026,
    startingValue: "150000.00",
    snapshotDate: "2020-01-01",
    settings: {
      targetAmount: "0.00",
      targetYear: 2046,
      monthlyContribution: "1000.00",
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
      tierFatEnabled: false,
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
      essentialMonthly: "1200.00",
      totalMonthly: "2000.00",
      derivedEssentialMonthly: "1200.00",
      derivedTotalMonthly: "2000.00",
      essentialMode: "derived",
      totalMode: "derived",
      monthsAvailable: 12,
      lookbackMonths: 12,
      aggregation: "mean",
      excludeCurrentMonth: false,
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
    actualReturn: {
      annualizedPercent: "7.50",
      fromDate: "2020-01-01",
      toDate: "2026-01-01",
      movementCount: 12,
      firstMovementDate: "2021-01-01",
      uncoveredMonths: 12,
    },
    actualReturnUnavailableReason: null,
    historicalScenario: null,
    suggestedScenario: { name: "Suggested", meanPercent: "8.0", stdDevPercent: "16.0", historical: false },
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
        name: "Conservative",
        meanPercent: "4.0",
        stdDevPercent: "5.0",
        historical: false,
        series: [{ year: 2026, value: "150000.00" }],
        percentiles: [{ year: 2026, p10: "150000.00", p25: "150000.00", p50: "150000.00", p75: "150000.00", p90: "150000.00" }],
        tierStats: [
          {
            tier: "lean",
            deterministicHitYear: 2036,
            probabilityOfReachingTarget: 0.75,
            medianYearReachingTarget: 2037,
            coastProbabilityOfReachingTarget: 0.5,
            coastMedianYearReachingTarget: 2042,
            coastTargetToday: "180000.00",
            requiredMonthlyToHitTarget: "900.00",
            drawdownSurvivalRate: 0.9,
            drawdownPercentiles: [],
          },
          {
            tier: "fire",
            deterministicHitYear: 2042,
            probabilityOfReachingTarget: 0.55,
            medianYearReachingTarget: 2043,
            coastProbabilityOfReachingTarget: 0.25,
            coastMedianYearReachingTarget: 2045,
            coastTargetToday: "300000.00",
            requiredMonthlyToHitTarget: "1600.00",
            drawdownSurvivalRate: 0.85,
            drawdownPercentiles: [],
          },
        ],
      },
      {
        name: "Base",
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

  it("FireTiersCard renders plain-language tier strip, reference badge, collapsed detail, and lets user dismiss stale snapshot warning", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <FireTiersCard projection={makeProjection()} currency="EUR" />
      </MemoryRouter>,
    );

    expect(screen.getByText("Essentials only")).toBeInTheDocument();
    expect(screen.getByText("Lean FIRE")).toBeInTheDocument();
    expect(screen.getByText("Your current spending")).toBeInTheDocument();
    expect(screen.getByText("Reference")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Adjust spending" })).toHaveAttribute("href", "/fire?tab=settings");
    expect(screen.getByText(/days old/)).toBeInTheDocument();
    expect(screen.getByText(/Starting from.*2020-01-01/)).toBeInTheDocument();

    const closeBtn = screen.getByRole("button", { name: /close/i });
    await user.click(closeBtn);
    expect(screen.queryByText(/days old/)).not.toBeInTheDocument();
  });

  it("FireWhatIfBar shows question buttons, active preview badge, and invokes reset/apply", async () => {
    const user = userEvent.setup();
    const base = makeProjection();
    const onChange = vi.fn();
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
        onChange={onChange}
        onReset={onReset}
        onApply={onApply}
      />,
    );

    expect(screen.getByText("Preview active (not saved)")).toBeInTheDocument();
    expect(screen.getByText(/Monthly spending/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /save.*500.*more/i }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ monthlyContribution: "1500.00" }));

    await user.click(screen.getByRole("button", { name: /spend 10% less/i }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ monthlySpending: "1800.00" }));

    await user.click(screen.getByRole("button", { name: "Reset" }));
    await user.click(screen.getByRole("button", { name: "Apply to settings" }));
    expect(onReset).toHaveBeenCalledTimes(1);
    expect(onApply).toHaveBeenCalledTimes(1);
  });

  it("FireScenarioTable renders matrix in three modes, shows shortfall, and omits steady-return year", async () => {
    const user = userEvent.setup();
    render(<FireScenarioTable projection={makeProjection()} currency="EUR" />);

    // Scenario names primary
    expect(screen.getByText("Conservative")).toBeInTheDocument();
    expect(screen.getByText("Base")).toBeInTheDocument();
    // Steady-return year removed
    expect(screen.queryByText("Steady-return year")).not.toBeInTheDocument();

    // Reach mode (default) shows shortfall for FIRE tier (1450 - 1000 = 450)
    expect(screen.getByText(/needs \+€450\.00\/month/)).toBeInTheDocument();
    expect(screen.getByText("72%")).toBeInTheDocument();

    // Lasts 30 years mode
    await user.click(screen.getByRole("button", { name: "Lasts 30 years" }));
    expect(screen.getByText("88%")).toBeInTheDocument();

    // Coast mode
    await user.click(screen.getByRole("button", { name: "If we stop contributing today" }));
    expect(screen.getByText("35%")).toBeInTheDocument();
  });

  it("FireHeroStrip displays headline, four numbers, explainer dismissal, and sandbox deltas", async () => {
    const user = userEvent.setup();
    const base = makeProjection();
    const preview = makeProjection({
      tiers: [
        base.tiers[0],
        { ...base.tiers[1], coveragePercent: 28.0 },
      ],
      scenarios: [
        base.scenarios[0],
        {
          ...base.scenarios[1],
          tierStats: [
            base.scenarios[1].tierStats[0],
            { ...base.scenarios[1].tierStats[1], medianYearReachingTarget: 2037 },
          ],
        },
      ],
    });

    const { rerender } = render(
      <FireHeroStrip
        projection={base}
        currency="EUR"
        onToggleWhatIf={vi.fn()}
      />,
    );

    expect(screen.getByText(/What is this page\?/)).toBeInTheDocument();
    expect(screen.getByText(/You have 25 % of what you need to live off your investments/)).toBeInTheDocument();
    expect(screen.getByText("2039")).toBeInTheDocument();
    expect(screen.getByText("38 %")).toBeInTheDocument();
    expect(screen.getByText("manual")).toBeInTheDocument();

    // Dismiss explainer and check persistence
    await user.click(screen.getByRole("button", { name: "Got it" }));
    expect(window.localStorage.getItem("fire-explainer-dismissed")).toBe("1");

    // Active sandbox with deltas
    rerender(
      <FireHeroStrip
        projection={preview}
        baselineProjection={base}
        currency="EUR"
        whatIfOpen={true}
        isWhatIfActive={true}
        onToggleWhatIf={vi.fn()}
        onBackToPlan={vi.fn()}
      />,
    );

    expect(screen.getByText("what-if")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Back to my plan" })).toBeInTheDocument();
    expect(screen.getByText("2039")).toBeInTheDocument();
    expect(screen.getByText("2037")).toBeInTheDocument();
    expect(screen.getByText("28 %")).toBeInTheDocument();
  });

  it("FireChart hides uncertainty band selector, shows toggle groups, and keeps annualized return in How is this calculated?", () => {
    render(<FireChart projection={makeProjection()} currency="EUR" />);

    expect(screen.queryByText(/Uncertainty band/)).not.toBeInTheDocument();
    expect(screen.getByText(/Other scenarios:/)).toBeInTheDocument();
    expect(screen.getByText(/Other targets:/)).toBeInTheDocument();
    expect(screen.getByText(/How is the projection calculated\?/)).toBeInTheDocument();
    expect(screen.getByText(/historical real annualized return is 7\.50%/)).toBeInTheDocument();
  });

  it("FireSettingsCard renders Plan questions, Advanced summaries, and supports manual spending override toggle", async () => {
    const user = userEvent.setup();
    const proj = makeProjection();
    const onChange = vi.fn();

    render(
      <FireSettingsCard
        form={proj.settings}
        errors={{}}
        isOwner={true}
        saving={false}
        saved={false}
        serverError={null}
        projection={proj}
        assetClasses={[{ code: "etfs" }, { code: "stocks" }]}
        currency="EUR"
        onChange={onChange}
        onScenarioErrorClear={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    expect(screen.getByText("Until which year do we project?")).toBeInTheDocument();
    expect(screen.getByText("How much do we withdraw per year once retired?")).toBeInTheDocument();
    expect(screen.getByText("Which target is your main reference?")).toBeInTheDocument();
    expect(screen.getByText("Which return scenario is your baseline?")).toBeInTheDocument();

    // Advanced section summaries
    expect(screen.getByText("2 · Conservative 4.0% · Base 6.0%")).toBeInTheDocument();
    expect(screen.getByText("30y · ×1.5")).toBeInTheDocument();
    expect(screen.getByText("none")).toBeInTheDocument();

    // Activate manual override on essential spending
    const overrideButtons = screen.getAllByRole("button", { name: "Override" });
    await user.click(overrideButtons[0]);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        essentialSpendingMode: "manual",
        manualEssentialSpending: "1200.00",
      }),
      undefined,
    );
  });
});
