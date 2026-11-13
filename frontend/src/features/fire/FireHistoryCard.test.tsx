import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { FireHistory } from "@/api/fire";
import { FireHistoryCard } from "./FireHistoryCard";
import { makeProjection } from "./fireTestProjection";

const today = { date: "2026-11-13", snapshotDate: "2026-10-19", current: true, coveragePercent: 17.1, estimatedYear: 2046, probability: 0.61, wealth: "130000.00", target: "760000.00" };

function history(over: Partial<FireHistory>): FireHistory {
  return { tier: "fire", horizonYear: 2050, points: [], omitted: [], manualContribution: true, manualSpending: false, attribution: null, ...over };
}

describe("FireHistoryCard", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("with no point says so plainly and offers no chart", () => {
    render(<FireHistoryCard history={history({})} isLoading={false} isError={false} projection={makeProjection()} currency="EUR" />);
    expect(screen.getByTestId("fire-history-summary")).toHaveTextContent(/Not enough data yet to show how you have done/);
    expect(screen.queryByRole("button", { name: "Show over time" })).not.toBeInTheDocument();
  });

  it("with a single snapshot gives today's figures in a sentence and no chart", () => {
    render(<FireHistoryCard history={history({ points: [today] })} isLoading={false} isError={false} projection={makeProjection()} currency="EUR" />);
    expect(screen.getByTestId("fire-history-summary")).toHaveTextContent("Today: 17% · 2046. There is no earlier point to compare with yet.");
    expect(screen.queryByRole("button", { name: "Show over time" })).not.toBeInTheDocument();
  });

  it("expanded, lists every point with its year and says why earlier ones are missing", async () => {
    const user = userEvent.setup();
    const past = { ...today, date: "2025-12-01", snapshotDate: "2025-12-01", current: false, coveragePercent: 14.2, estimatedYear: null };
    render(
      <FireHistoryCard
        history={history({ points: [past, today], omitted: [{ reason: "insufficient_spending_history", count: 18 }] })}
        isLoading={false}
        isError={false}
        projection={makeProjection()}
        currency="EUR"
      />,
    );
    await user.click(screen.getByRole("button", { name: "Show over time" }));
    const table = screen.getByRole("table");
    expect(table).toHaveTextContent("Today17.1%2046");
    expect(table).toHaveTextContent("1 Dec 202514.2%Beyond 2050");
    expect(screen.getByText(/18 earlier points left out/)).toBeInTheDocument();
    expect(screen.getByText(/today's amount .* is used at every point/)).toBeInTheDocument();
  });
});
