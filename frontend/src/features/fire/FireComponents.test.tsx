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
import { makeProjection } from "./fireTestProjection";

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
    expect(screen.getByText(/needs \+€450\/month/)).toBeInTheDocument();
    expect(screen.getByText("72%")).toBeInTheDocument();

    // Lasts 30 years mode
    await user.click(screen.getByRole("button", { name: "If reached, lasts 30 years" }));
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
            { ...base.scenarios[1].tierStats[1], estimatedYear: 2037 },
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
    expect(screen.getByText(/historical actual annualized return is 7\.50 %/)).toBeInTheDocument();
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
    expect(screen.getByText("2 · Conservative 4 % · Base 6 %")).toBeInTheDocument();
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

  it("shows section-level default badge only for current seeds, per-row ⓘ, restore confirmation, and Part C horizon/inflation polish", async () => {
    const user = userEvent.setup();
    const proj = makeProjection();
    proj.settings.targetYear = 2033; // 7 years from 2026 => triggers <10y horizon hint
    const onChange = vi.fn();

    const { rerender } = render(
      <FireSettingsCard
        form={proj.settings}
        errors={{}}
        isOwner={true}
        saving={false}
        saved={false}
        serverError={null}
        projection={proj}
        assetClasses={[{ code: "etfs" }]}
        currency="EUR"
        onChange={onChange}
        onScenarioErrorClear={vi.fn()}
        onSave={vi.fn()}
      />,
    );

    // Part C: inflation question label without SWR in secondary label
    expect(screen.getByText("How much do prices rise each year?")).toBeInTheDocument();
    expect(screen.getByText("Expected inflation (%/year)")).toBeInTheDocument();

    // Part C: horizon distance & +10 years action
    expect(screen.getByText(/7 years from now\./)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "+10 years" }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ targetYear: 2043 }), ["targetYear"]);

    // Part B: non-default scenario set has NO default badge
    expect(screen.queryByText("default")).not.toBeInTheDocument();

    // Part B: Restore defaults opens confirmation listing current scenarios and restores seeds + referenceScenarioIndex=1
    await user.click(screen.getByRole("button", { name: "Restore defaults" }));
    expect(screen.getByText("Replace current scenarios with defaults?")).toBeInTheDocument();
    expect(screen.getByText("Conservative (4 % ±5 %)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Replace scenarios" }));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        referenceScenarioIndex: 1,
        returnScenarios: [
          { name: "Conservative", meanPercent: "4.0", stdDevPercent: "5.0", historical: false },
          { name: "Base", meanPercent: "6.5", stdDevPercent: "10.0", historical: false },
          { name: "Optimistic", meanPercent: "8.5", stdDevPercent: "15.0", historical: false },
        ],
      }),
      undefined,
    );

    // Rerender with current seeds => section-level default badge appears and per-row ⓘ shows portfolio note
    const seededSettings = {
      ...proj.settings,
      returnScenarios: [
        { name: "Conservative", meanPercent: "4.0", stdDevPercent: "5.0", historical: false },
        { name: "Base", meanPercent: "6.5", stdDevPercent: "10.0", historical: false },
        { name: "Optimistic", meanPercent: "8.5", stdDevPercent: "15.0", historical: false },
      ],
    };
    rerender(
      <FireSettingsCard
        form={seededSettings}
        errors={{}}
        isOwner={true}
        saving={false}
        saved={false}
        serverError={null}
        projection={proj}
        assetClasses={[{ code: "etfs" }]}
        currency="EUR"
        onChange={onChange}
        onScenarioErrorClear={vi.fn()}
        onSave={vi.fn()}
      />,
    );
    expect(screen.getByText("default")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Details for Base" }));
    expect(screen.getByText(/Nominal arithmetic mean: 6\.5 %\/yr/)).toBeInTheDocument();
    expect(screen.getByText(/Typical compound growth: 6\.03 %\/yr/)).toBeInTheDocument();
    expect(screen.getByText(/Resembles a mixed 60\/40 portfolio\./)).toBeInTheDocument();
  });
});

/** Base fire cell below 50 %: some trials reach it (conditional median 2041) but not half of all of them. */
function belowHalfProjection(): FireProjection {
  const p = makeProjection();
  p.scenarios[1].tierStats[1] = {
    ...p.scenarios[1].tierStats[1],
    probabilityOfReachingTarget: 0.39,
    estimatedYear: null,
    medianYearAmongReaching: 2041,
  };
  return p;
}

function renderSettings(proj: FireProjection, form = proj.settings) {
  return render(
    <FireSettingsCard
      form={form}
      errors={{}}
      isOwner={true}
      saving={false}
      saved={false}
      serverError={null}
      projection={proj}
      assetClasses={[{ code: "etfs" }]}
      currency="EUR"
      onChange={vi.fn()}
      onScenarioErrorClear={vi.fn()}
      onSave={vi.fn()}
    />,
  );
}

describe("FIRE estimated year is the median over all trials", () => {
  beforeEach(async () => {
    window.localStorage.clear();
    await i18n.changeLanguage("en");
  });

  it("photo, tier strip and matrix show no year when fewer than half of all trials reach the reference target", () => {
    const p = belowHalfProjection();
    render(
      <MemoryRouter>
        <FireHeroStrip projection={p} currency="EUR" />
        <FireTiersCard projection={p} currency="EUR" />
        <FireScenarioTable projection={p} currency="EUR" />
      </MemoryRouter>,
    );
    expect(screen.queryByText(/2041/)).not.toBeInTheDocument();
    // Four numbers + tier strip + matrix cell.
    expect(screen.getAllByText(/Beyond 2046/).length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText(/most likely not get there before 2046 \(39% chance\)/)).toBeInTheDocument();
  });

  it("matrix shows the year of half of all trials in reach, lasts and coast modes", async () => {
    const user = userEvent.setup();
    const p = makeProjection();
    p.scenarios[1].tierStats[1] = { ...p.scenarios[1].tierStats[1], estimatedYear: 2040, medianYearAmongReaching: 2036 };
    render(<FireScenarioTable projection={p} currency="EUR" />);
    expect(screen.getByText("2040")).toBeInTheDocument();
    expect(screen.queryByText("2036")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "If reached, lasts 30 years" }));
    expect(screen.getByText("2040")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "If we stop contributing today" }));
    // Coast at 35 % has no year even though its conditional median is 2044.
    expect(screen.queryByText("2044")).not.toBeInTheDocument();
    expect(screen.getAllByText("Beyond 2046").length).toBeGreaterThanOrEqual(1);
  });

  it("horizon hint fires when the reference cell has no year, and not when it has one", () => {
    const { unmount } = renderSettings(belowHalfProjection());
    expect(screen.getByRole("button", { name: "+10 years" })).toBeInTheDocument();
    unmount();
    renderSettings(makeProjection());
    expect(screen.queryByRole("button", { name: "+10 years" })).not.toBeInTheDocument();
  });

  it("explains the estimated year in the chart's and the matrix's How is this calculated?", () => {
    render(
      <>
        <FireChart projection={makeProjection()} currency="EUR" />
        <FireScenarioTable projection={makeProjection()} currency="EUR" />
      </>,
    );
    expect(screen.getAllByText(/first year by which half of the simulations have reached the target/)).toHaveLength(2);
  });
});

describe("FIRE formatting and labels", () => {
  beforeEach(async () => {
    window.localStorage.clear();
    await i18n.changeLanguage("es");
  });

  it("photo uses whole euros with a thousands separator and the savings-rate sentence", () => {
    render(<FireHeroStrip projection={makeProjection()} currency="EUR" />);
    expect(screen.getByText("1.000 €")).toBeInTheDocument();
    expect(screen.getByText("De cada 100 € que entran, guardáis 38")).toBeInTheDocument();
  });

  it("tier strip shows whole euros and no repeated FIRE-target line", () => {
    render(
      <MemoryRouter>
        <FireTiersCard projection={makeProjection()} currency="EUR" />
      </MemoryRouter>,
    );
    expect(screen.getByText("360.000 €")).toBeInTheDocument();
    expect(screen.queryByText(/Lo que necesitas para vivir de tus inversiones/)).not.toBeInTheDocument();
    expect(screen.queryByText(/objetivo FIRE/i)).not.toBeInTheDocument();
  });

  it("own target has one name and no duplicated secondary label", () => {
    const p = makeProjection();
    p.settings.tierCustomEnabled = true;
    p.settings.targetAmount = "500000.00";
    p.tiers.push({ ...p.tiers[1], key: "custom", monthlyNetSpending: null, annualNetSpending: null, annualGrossSpending: null, estimatedAnnualTax: null, targetToday: "500000.00" });
    render(
      <MemoryRouter>
        <FireTiersCard projection={p} currency="EUR" />
      </MemoryRouter>,
    );
    expect(screen.getAllByText("Objetivo propio")).toHaveLength(1);
  });

  it("matrix shows whole euros and locale percentages", () => {
    render(<FireScenarioTable projection={makeProjection()} currency="EUR" />);
    expect(screen.getByText(/necesita \+450 €\/mes/)).toBeInTheDocument();
    expect(screen.getByText("6,0 % ±12,0 %")).toBeInTheDocument();
  });

  it("assumptions panel splits SWR and inflation, uses locale decimals and labels the gain share", () => {
    const p = makeProjection();
    p.settings.applyCapitalGainsTax = true;
    render(<FireChart projection={p} currency="EUR" />);
    expect(screen.getByText("Inflación esperada")).toBeInTheDocument();
    expect(screen.getByText("2,00 %")).toBeInTheDocument();
    expect(screen.getByText("4,00 %")).toBeInTheDocument();
    expect(screen.getByText(/La parte de cada retirada que es ganancia/)).toBeInTheDocument();
    expect(screen.getByText("Tasa de ahorro")).toBeInTheDocument();
    expect(screen.getByText("150.000 €")).toBeInTheDocument();
  });

  it("settings: plain tier names first, translated summary, and feminine gain source", () => {
    const p = makeProjection();
    p.settings.spendingAggregation = "median";
    p.settings.applyCapitalGainsTax = true;
    renderSettings(p);
    expect(screen.getByText("(Lean FIRE)")).toBeInTheDocument();
    expect(screen.getAllByText("Solo lo esencial").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/12m · mediana · esencial/)).toBeInTheDocument();
    expect(screen.getByText(/ganancia derivada/)).toBeInTheDocument();
    expect(screen.getByText("Años de retiro")).toBeInTheDocument();
  });

  it("spending help is instantiated with the configured window, method and current-month rule", () => {
    const p = makeProjection();
    renderSettings(p, { ...p.settings, spendingLookbackMonths: 3, spendingAggregation: "median", excludeCurrentMonth: true });
    expect(
      screen.getByText("Mediana mensual de los últimos 3 meses del gasto en categorías esenciales, sin contar el mes en curso."),
    ).toBeInTheDocument();
    expect(screen.getByText("Mediana mensual de los últimos 3 meses de todo el gasto, sin contar el mes en curso.")).toBeInTheDocument();
  });

  it("spending help says the current month is included when it is", async () => {
    await i18n.changeLanguage("en");
    const p = makeProjection();
    renderSettings(p);
    expect(
      screen.getByText("Monthly average of the last 12 months of all spending, including the current month."),
    ).toBeInTheDocument();
  });

  it("restore defaults is a secondary button like its neighbours", () => {
    renderSettings(makeProjection());
    const restore = screen.getByRole("button", { name: "Restaurar valores por defecto" });
    const neighbour = screen.getByRole("button", { name: "Añadir escenario" });
    expect(restore.className).toBe(neighbour.className);
  });

  it("settings help texts use whole euros and locale percentages", () => {
    const p = makeProjection();
    p.settings.applyCapitalGainsTax = true;
    p.settings.safeWithdrawalRatePct = "4.00";
    renderSettings(p);
    expect(screen.getByText(/Al 4 % tu objetivo FIRE es 600\.000 €; al 3,5 % sería 685\.714 €/)).toBeInTheDocument();
    expect(screen.getByText(/1 tramos 19–19 % · ganancia derivada 40 %/)).toBeInTheDocument();
    expect(screen.getByText(/el 40,00 % de una retirada hoy sería plusvalía/)).toBeInTheDocument();
    expect(screen.getByText(/esencial 1\.200 € · total 2\.000 €/)).toBeInTheDocument();
  });

  it("writes the Fat multiplier with the locale's decimal separator and no trailing zeros everywhere", () => {
    const p = makeProjection();
    p.settings.fatMultiplier = "1.50";
    p.settings.tierFatEnabled = true;
    p.tiers.push({ ...p.tiers[1], key: "fat", targetToday: "900000.00", annualNetSpending: "36000.00", annualGrossSpending: "36000.00" });
    for (const sc of p.scenarios) sc.tierStats.push({ ...sc.tierStats[1], tier: "fat" });
    render(
      <MemoryRouter>
        <FireTiersCard projection={p} currency="EUR" />
        <FireScenarioTable projection={p} currency="EUR" />
      </MemoryRouter>,
    );
    expect(screen.getAllByText("Tu gasto ×1,5")).toHaveLength(2);
    expect(screen.getByText(/cubre el gasto total × 1,5\)/)).toBeInTheDocument();
    expect(screen.queryByText(/1\.50|1,50/)).not.toBeInTheDocument();
  });

  it("settings write the Fat multiplier the same way in names, summary and help", () => {
    const p = makeProjection();
    p.settings.fatMultiplier = "1.50";
    p.tiers.push({ ...p.tiers[1], key: "fat", targetToday: "900000.00" });
    renderSettings(p);
    expect(screen.getAllByText("Tu gasto ×1,5").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("30a · ×1,5")).toBeInTheDocument();
    expect(screen.getByText(/Fat FIRE asume gasto × 1,5: objetivo 900\.000 €/)).toBeInTheDocument();
  });
});

