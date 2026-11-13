import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "@/i18n";
import type { FireLevers } from "@/api/fire";
import { FireLeversCard } from "./FireLeversCard";

function levers(): FireLevers {
  const row = (over: Partial<FireLevers["rows"][number]>): FireLevers["rows"][number] => ({
    key: "contribute_more",
    outOfYourHands: false,
    applicable: true,
    preview: { monthlyContribution: "1100.00" },
    estimatedYear: 2045,
    probability: 0.6,
    yearChange: -1,
    probabilityChangePoints: 4,
    amount: null,
    percent: null,
    withdrawalFromPct: null,
    withdrawalToPct: null,
    ...over,
  });
  return {
    tier: "fire",
    horizonYear: 2046,
    baselineYear: 2046,
    baselineProbability: 0.56,
    rows: [
      row({ key: "returns_up", outOfYourHands: true, preview: { returnShiftPct: "1" }, percent: "1", probabilityChangePoints: 12.3, yearChange: -2, estimatedYear: 2044 }),
      row({ key: "lump_sum", preview: { lumpSum: "10000" }, amount: "10000", probabilityChangePoints: 5.1 }),
      row({ key: "contribute_more", amount: "100", probabilityChangePoints: 3.4 }),
      row({ key: "spend_less", preview: { monthlySpending: "1900.00" }, percent: "5", probabilityChangePoints: 2.2, yearChange: 0, estimatedYear: 2046 }),
      row({ key: "safer_withdrawal", preview: { safeWithdrawalRatePct: "3.5" }, withdrawalFromPct: "4.0", withdrawalToPct: "3.5", probabilityChangePoints: -18.7, yearChange: null, estimatedYear: null, probability: 0.37 }),
      row({ key: "returns_down", outOfYourHands: true, preview: { returnShiftPct: "-1" }, percent: "1", probabilityChangePoints: -14, yearChange: null, estimatedYear: null }),
    ],
  };
}

describe("FireLeversCard", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("lists the levers in the order given, with delays shown as delays and returns marked as not in your hands", () => {
    render(<FireLeversCard scenarioName="Base" levers={levers()} isLoading={false} isError={false} currency="EUR" onOpen={vi.fn()} />);
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual([
      expect.stringContaining("Returns one point higher"),
      expect.stringContaining("A one-off €10,000 today"),
      expect.stringContaining("Put in €100 more a month"),
      expect.stringContaining("Spend 5% less"),
      expect.stringContaining("Withdraw 3.5% instead of 4%"),
      expect.stringContaining("Returns one point lower"),
    ]);
    expect(within(rows[0]).getByText("not in your hands")).toBeInTheDocument();
    expect(within(rows[4]).getByText("−18.7 pts")).toBeInTheDocument();
    expect(within(rows[4]).getByText("Beyond 2046")).toBeInTheDocument();
    expect(within(rows[0]).getByText("2 years sooner")).toBeInTheDocument();
    expect(within(rows[3]).getByText("same year")).toBeInTheDocument();
  });

  it("opening a row hands its what-if values over", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(<FireLeversCard scenarioName="Base" levers={levers()} isLoading={false} isError={false} currency="EUR" onOpen={onOpen} />);
    await user.click(screen.getByRole("button", { name: /Withdraw 3.5% instead of 4%/ }));
    expect(onOpen).toHaveBeenCalledWith({ safeWithdrawalRatePct: "3.5" });
  });

  it("a lever that cannot move an own target says so and cannot be opened", () => {
    const data = levers();
    data.tier = "custom";
    data.rows[3] = { ...data.rows[3], applicable: false, preview: null, probability: null, estimatedYear: null, probabilityChangePoints: null, yearChange: null };
    render(<FireLeversCard scenarioName="Base" levers={data} isLoading={false} isError={false} currency="EUR" onOpen={vi.fn()} />);
    expect(screen.getByText("Doesn't change your own target")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Spend 5% less/ })).not.toBeInTheDocument();
  });
});
