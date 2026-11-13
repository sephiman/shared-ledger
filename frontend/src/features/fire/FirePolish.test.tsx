import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import i18n from "@/i18n";
import type { FireHistory, FireLevers } from "@/api/fire";
import { FireHeroStrip } from "./FireHeroStrip";
import { FireTiersCard } from "./FireTiersCard";
import { FireHistoryCard } from "./FireHistoryCard";
import { FireLeversSection } from "./FireLeversSection";
import { leversSummary } from "./FireLeversCard";
import { makeProjection } from "./fireTestProjection";

function levers(): FireLevers {
  const base = {
    outOfYourHands: false, applicable: true, estimatedYear: 2046, probability: 0.6, yearChange: 0,
    amount: null, percent: null, withdrawalFromPct: null, withdrawalToPct: null,
  };
  return {
    tier: "fire", horizonYear: 2046, baselineYear: 2046, baselineProbability: 0.52,
    rows: [
      { ...base, key: "returns_up", outOfYourHands: true, preview: { returnShiftPct: "1" }, percent: "1", probabilityChangePoints: 13.0 },
      { ...base, key: "spend_less", preview: { monthlySpending: "1900.00" }, percent: "5", probabilityChangePoints: 7.6 },
      { ...base, key: "contribute_more", preview: { monthlyContribution: "1100.00" }, amount: "100", probabilityChangePoints: 3.8 },
      { ...base, key: "safer_withdrawal", preview: { safeWithdrawalRatePct: "3.5" }, withdrawalFromPct: "4.0", withdrawalToPct: "3.5", probabilityChangePoints: -14.5 },
    ],
  };
}

describe("FIRE page polish", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("the estimated year is explained as half of the simulations, like the chart marker", () => {
    render(<FireHeroStrip projection={makeProjection()} currency="EUR" />);
    expect(screen.getByText("half of the simulations")).toBeInTheDocument();
    expect(screen.queryByText(/Monte Carlo median/)).not.toBeInTheDocument();
  });

  it("reads la mitad de las simulaciones in Spanish", async () => {
    await i18n.changeLanguage("es");
    render(<FireHeroStrip projection={makeProjection()} currency="EUR" />);
    expect(screen.getByText("la mitad de las simulaciones")).toBeInTheDocument();
  });

  it("never mixes percent spacings on the photo: English writes 21%, Spanish 21 %", async () => {
    const { unmount } = render(
      <MemoryRouter>
        <FireHeroStrip projection={makeProjection()} currency="EUR" />
        <FireTiersCard projection={makeProjection()} currency="EUR" />
      </MemoryRouter>,
    );
    const en = document.body.textContent ?? "";
    expect(en).toMatch(/\d%/);
    expect(en).not.toMatch(/\d[\s\u00a0]%/);
    unmount();

    await i18n.changeLanguage("es");
    render(
      <MemoryRouter>
        <FireHeroStrip projection={makeProjection()} currency="EUR" />
        <FireTiersCard projection={makeProjection()} currency="EUR" />
      </MemoryRouter>,
    );
    const es = document.body.textContent ?? "";
    expect(es).toMatch(/\d %/);
    expect(es).not.toMatch(/\d%/);
  });

  it("the levers summary names the biggest lever in your hands and its change in chance", () => {
    expect(leversSummary(levers(), "EUR", "en", i18n.t)).toBe("Biggest lever: spend 5% less (+8 points)");
  });

  it("the levers summary says what the section holds until the levers arrive, then the result", () => {
    const { rerender } = render(
      <FireLeversSection scenarioName="Base" levers={undefined} isLoading={true} isError={false} currency="EUR" onOpen={() => {}} />,
    );
    expect(screen.getByText("Six possible changes and how much each one moves your year")).toBeInTheDocument();
    rerender(<FireLeversSection scenarioName="Base" levers={levers()} isLoading={false} isError={false} currency="EUR" onOpen={() => {}} />);
    expect(screen.getByText("Biggest lever: spend 5% less (+8 points)")).toBeInTheDocument();
  });

  it("the new sections' titles carry no technical term; it lives in How is this calculated?", async () => {
    const history: FireHistory = {
      tier: "fire", horizonYear: 2050, omitted: [], manualContribution: true, manualSpending: false, attribution: null,
      points: [
        { date: "2025-11-10", snapshotDate: "2025-11-10", current: false, coveragePercent: 14.2, estimatedYear: 2049, probability: 0.52, wealth: "1", target: "7" },
        { date: "2026-11-13", snapshotDate: "2026-10-19", current: true, coveragePercent: 17.1, estimatedYear: 2046, probability: 0.61, wealth: "1", target: "6" },
      ],
    };
    render(
      <>
        <FireHistoryCard history={history} isLoading={false} isError={false} projection={makeProjection()} currency="EUR" />
        <FireLeversSection scenarioName="Base" levers={levers()} isLoading={false} isError={false} currency="EUR" onOpen={() => {}} />
      </>,
    );
    expect(screen.getByText("How you have done").textContent).toBe("How you have done");
    expect(screen.getByText("What moves your year most").textContent).toBe("What moves your year most");
    expect(screen.queryByText("(coverage over time)")).not.toBeInTheDocument();
    expect(screen.queryByText("(sensitivity)")).not.toBeInTheDocument();
  });
});
