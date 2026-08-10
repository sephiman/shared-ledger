import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { HoldingAssetClass, HoldingSummary, PortfolioSummary } from "@/api/portfolio";
import i18n from "@/i18n";

const summaryRef: { current: PortfolioSummary } = { current: null as unknown as PortfolioSummary };

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ user: { portfolioReturnBasis: "OPEN_COST" } }),
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

vi.mock("@/api/portfolio", async (importOriginal) => {
  const mutation = () => ({ mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false });
  return {
    ...(await importOriginal<typeof import("@/api/portfolio")>()),
    usePortfolioSummary: () => ({ data: summaryRef.current }),
    useCreateHolding: mutation,
    useUpdateHolding: mutation,
    useDeleteHolding: mutation,
    useUnlinkHolding: mutation,
    useRefreshPrices: mutation,
  };
});

const { HoldingsTab } = await import("./HoldingsTab");

function holding(
  symbol: string,
  currentValue: string | null,
  over: { assetClass?: HoldingAssetClass; closed?: boolean; netQuantity?: string } = {},
): HoldingSummary {
  const { assetClass = "crypto", closed = false, netQuantity = closed ? "0" : "1" } = over;
  return {
    holding: {
      id: symbol,
      assetClass,
      symbol,
      label: null,
      nativeCurrency: "EUR",
      isin: null,
      provider: "coingecko",
      providerSymbol: symbol.toLowerCase(),
      linked: currentValue != null,
      active: true,
      lots: [],
      netQuantity,
      remainingCostBasis: "100.00",
      realizedPnl: "0.00",
      closed,
      createdAt: "2026-01-01T00:00:00Z",
    },
    currentPrice: currentValue == null ? null : "100",
    priceCurrency: "EUR",
    priceAsOf: "2026-07-29",
    priceObservedAt: "2026-07-29T09:00:00Z",
    stale: false,
    currentValue,
    unrealizedPnl: currentValue == null ? null : "0.00",
    unrealizedPnlPct: null,
    realizedPnl: "0.00",
    soldCostBasis: "0.00",
    totalReturn: null,
    weight: null,
  };
}

function summaryOf(holdings: HoldingSummary[]): PortfolioSummary {
  return {
    asOfDate: "2026-07-29",
    holdings,
    totalCostBasis: "1000.00",
    totalValue: "1500.00",
    totalRealizedPnl: "0.00",
    totalUnrealizedPnl: "500.00",
    totalReturn: "500.00",
    totalSoldCostBasis: "0.00",
    unrealizedPnlPct: null,
    realizedPnlPct: null,
    totalReturnPct: null,
    moneyWeightedReturn: {
      value: null,
      annualized: true,
      from: null,
      to: "2026-07-29",
      flowCount: 0,
      terminalValue: null,
      unavailableReason: "no_flows",
    },
    moneyWeightedReturnByClass: {},
    byClass: {},
    anyStale: false,
    anyUnpriced: false,
  };
}

/** Both layouts (mobile list and desktop table) are in the DOM under jsdom, so a visible row is two nodes. */
function rowCount(symbol: string): number {
  return screen.queryAllByText(symbol).length;
}

function isVisible(symbol: string): boolean {
  return rowCount(symbol) > 0;
}

function show(holdings: HoldingSummary[]) {
  summaryRef.current = summaryOf(holdings);
  render(<HoldingsTab />);
  return userEvent.setup();
}

const OPEN = holding("BTC", "1200.00");
const SECOND_OPEN = holding("VWCE", "300.00", { assetClass: "etf" });
const CLOSED = holding("DOGE", "0.00", { closed: true });
const DUST = holding("ETH", "0.0000004");
const CLOSED_ETF = holding("IWDA", "0.00", { assetClass: "etf", closed: true });

beforeEach(() => {
  localStorage.clear();
});

afterEach(async () => {
  cleanup();
  localStorage.clear();
  await i18n.changeLanguage("en");
});

describe("hiding closed and dust holdings", () => {
  it("leaves both out of the table by default", () => {
    show([OPEN, CLOSED, DUST]);
    expect(isVisible("BTC")).toBe(true);
    expect(isVisible("DOGE")).toBe(false);
    expect(isVisible("ETH")).toBe(false);
  });

  it("discloses what it hid in the holdings count", () => {
    show([OPEN, CLOSED, DUST]);
    expect(screen.getByText("1 holding (+2 closed)")).toBeInTheDocument();
  });

  it("drops the suffix when there is nothing to hide", () => {
    show([OPEN, SECOND_OPEN]);
    expect(screen.getByText("2 holdings")).toBeInTheDocument();
    expect(screen.queryByLabelText("Show closed")).not.toBeInTheDocument();
  });

  it("never hides an unpriced open holding — its value is unknown, not small", () => {
    show([OPEN, holding("FUND", null)]);
    expect(isVisible("FUND")).toBe(true);
    expect(screen.getByText("2 holdings")).toBeInTheDocument();
  });

  it("reveals the rows inline when the toggle goes on, and stops counting them as hidden", async () => {
    const user = show([OPEN, CLOSED, DUST]);
    await user.click(screen.getByRole("checkbox", { name: "Show closed" }));
    expect(isVisible("DOGE")).toBe(true);
    expect(isVisible("ETH")).toBe(true);
    expect(screen.getByText("3 holdings")).toBeInTheDocument();
  });

  it("remembers the toggle in this browser across a remount", async () => {
    const user = show([OPEN, CLOSED]);
    await user.click(screen.getByRole("checkbox", { name: "Show closed" }));
    expect(localStorage.getItem("sl.portfolio.showClosed")).toBe("1");

    cleanup();
    render(<HoldingsTab />);
    expect(isVisible("DOGE")).toBe(true);
    expect(screen.getByRole("checkbox", { name: "Show closed" })).toBeChecked();
  });

  it("keeps the summary cards over every holding, toggle or not", async () => {
    const user = show([OPEN, CLOSED, DUST]);
    // The whole-portfolio total, not the sum of the rows on screen — hiding is display-only.
    expect(screen.getByText("€1,500.00")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Show closed" }));
    expect(screen.getByText("€1,500.00")).toBeInTheDocument();
  });

  it("composes with the asset-type filter", async () => {
    const user = show([OPEN, SECOND_OPEN, CLOSED_ETF]);
    await user.selectOptions(screen.getByRole("combobox"), "etf");
    expect(isVisible("VWCE")).toBe(true);
    expect(isVisible("IWDA")).toBe(false);
    expect(isVisible("BTC")).toBe(false);

    await user.click(screen.getByRole("checkbox", { name: "Show closed" }));
    expect(isVisible("IWDA")).toBe(true);
    expect(isVisible("BTC")).toBe(false);
  });

  it("explains an all-closed portfolio instead of showing an empty table", async () => {
    const user = show([CLOSED, DUST]);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByText('2 closed holdings are hidden. Turn on "Show closed" to see them.')).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "Show closed" }));
    expect(isVisible("DOGE")).toBe(true);
  });

  it("keeps the first-run empty state for a portfolio with no holdings at all", () => {
    show([]);
    expect(screen.getByText(/No holdings yet/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Show closed" })).not.toBeInTheDocument();
  });

  it("translates the toggle and the count", async () => {
    await i18n.changeLanguage("es");
    show([OPEN, CLOSED, DUST]);
    expect(screen.getByText("1 posición (+2 cerradas)")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Mostrar cerradas" })).toBeInTheDocument();
  });
});
