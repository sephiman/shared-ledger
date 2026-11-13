import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { FireHistory } from "@/api/fire";
import { createQueryClient } from "@/lib/queryClient";
import { FireTiersCard } from "./FireTiersCard";
import { FireChart } from "./FireChart";
import { FireHistoryCard } from "./FireHistoryCard";
import { FireAttributionCard } from "./FireAttributionCard";
import { makeProjection } from "./fireTestProjection";

const history: FireHistory = {
  tier: "fire",
  horizonYear: 2050,
  omitted: [],
  manualContribution: true,
  manualSpending: false,
  points: [
    { date: "2025-12-01", snapshotDate: "2025-12-01", current: false, coveragePercent: 14.2, estimatedYear: 2049, probability: 0.52, wealth: "100000.00", target: "700000.00" },
    { date: "2026-11-13", snapshotDate: "2026-10-19", current: true, coveragePercent: 17.1, estimatedYear: 2046, probability: 0.61, wealth: "130000.00", target: "760000.00" },
  ],
  attribution: {
    fromDate: "2025-12-01", toDate: "2026-11-13", merged: false, totalPoints: "3.0", contributedPoints: "2.1", marketPoints: "1.4",
    wealthPoints: "3.5", needPoints: "-0.5", wealthFrom: "100000.00", wealthTo: "130000.00", targetFrom: "700000.00",
    targetTo: "760000.00", contributedAmount: "18000.00", marketAmount: "12000.00",
  },
};

function page(children: ReactNode) {
  return (
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

async function renderAll() {
  const user = userEvent.setup();
  render(
    page(
      <>
        <FireTiersCard projection={makeProjection()} currency="EUR" />
        <FireChart projection={makeProjection()} currency="EUR" />
        <FireHistoryCard history={history} isLoading={false} isError={false} projection={makeProjection()} currency="EUR" />
        <FireAttributionCard householdId="h1" history={history} currency="EUR" noSpendingSince={null} />
      </>,
    ),
  );
  for (const name of [/Show over time|Ver en el tiempo/, /Show breakdown|Ver desglose/]) {
    await user.click(screen.getByRole("button", { name }));
  }
  return document.body.textContent ?? "";
}

describe("FIRE dates and legend", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("writes every date as day, month name, year and never as an ISO date (English)", async () => {
    const text = await renderAll();
    expect(text).not.toMatch(/\d{4}-\d{2}-\d{2}/);
    expect(text).toContain("1 Jan 2020"); // tier strip note and Assumptions (latest snapshot)
    expect(text).toContain("1 Dec 2025"); // history table and attribution
  });

  it("does the same in Spanish", async () => {
    await i18n.changeLanguage("es");
    const text = await renderAll();
    expect(text).not.toMatch(/\d{4}-\d{2}-\d{2}/);
    expect(text).toContain("1 ene 2020");
    expect(text).toContain("1 dic 2025");
  });

  it("names the history line as investments, like the tier strip note", async () => {
    expect(i18n.t("fire.historical_wealth")).toBe("Qualifying investments, history");
    await i18n.changeLanguage("es");
    expect(i18n.t("fire.historical_wealth")).toBe("Histórico de inversiones elegibles");
  });
});
