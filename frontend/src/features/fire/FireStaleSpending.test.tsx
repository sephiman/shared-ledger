import type { ReactNode } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { FireHistory } from "@/api/fire";
import { createQueryClient } from "@/lib/queryClient";
import { FireHeroStrip } from "./FireHeroStrip";
import { FireAttributionCard } from "./FireAttributionCard";
import { makeProjection } from "./fireTestProjection";

const history: FireHistory = {
  tier: "fire",
  horizonYear: 2050,
  points: [
    { date: "2025-11-10", snapshotDate: "2025-11-10", current: false, coveragePercent: 14.2, estimatedYear: 2049, probability: 0.52, wealth: "100000.00", target: "700000.00" },
    { date: "2026-11-13", snapshotDate: "2026-10-19", current: true, coveragePercent: 17.1, estimatedYear: 2046, probability: 0.61, wealth: "130000.00", target: "760000.00" },
  ],
  omitted: [],
  manualContribution: true,
  manualSpending: false,
  attribution: {
    fromDate: "2025-11-10", toDate: "2026-11-13", merged: false, totalPoints: "3.0", contributedPoints: "2.1", marketPoints: "1.4",
    wealthPoints: "3.5", needPoints: "-0.5", wealthFrom: "100000.00", wealthTo: "130000.00", targetFrom: "700000.00",
    targetTo: "760000.00", contributedAmount: "18000.00", marketAmount: "12000.00",
  },
};

function withQueries(children: ReactNode) {
  return <QueryClientProvider client={createQueryClient()}>{children}</QueryClientProvider>;
}

const stale = makeProjection();
stale.spending = { ...stale.spending, noSpendingSince: "2026-08" };

describe("stale spending notice", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("the photo warns, naming the last month with spending", () => {
    render(<FireHeroStrip projection={stale} currency="EUR" />);
    expect(screen.getByText("No spending recorded since August 2026 — the target is probably understated.")).toBeInTheDocument();
  });

  it("the photo says nothing when spending is up to date", () => {
    render(<FireHeroStrip projection={makeProjection()} currency="EUR" />);
    expect(screen.queryByText(/No spending recorded since/)).not.toBeInTheDocument();
  });

  it("the attribution sentence carries the same notice", () => {
    render(withQueries(<FireAttributionCard householdId="h1" history={history} currency="EUR" noSpendingSince="2026-08" />));
    expect(screen.getByTestId("fire-attribution-sentence")).toHaveTextContent(/You gained 3.0 points/);
    expect(screen.getByText("No spending recorded since August 2026 — the target is probably understated.")).toBeInTheDocument();
  });

  it("reads in Spanish with the month instantiated", async () => {
    await i18n.changeLanguage("es");
    render(<FireHeroStrip projection={stale} currency="EUR" />);
    expect(
      screen.getByText("No hay gastos registrados desde agosto de 2026: el objetivo probablemente se queda corto."),
    ).toBeInTheDocument();
  });
});
