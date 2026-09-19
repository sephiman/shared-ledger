import { describe, expect, it } from "vitest";
import type { HoldingSummary } from "@/api/portfolio";
import { nextSort, sortHoldings } from "./holdingsSort";

function row(symbol: string, over: { currentValue?: string | null; netQuantity?: string } = {}): HoldingSummary {
  const { currentValue = "100.00", netQuantity = "1" } = over;
  return {
    holding: {
      id: symbol,
      assetClass: "crypto",
      symbol,
      label: null,
      nativeCurrency: "EUR",
      isin: null,
      provider: null,
      providerSymbol: null,
      linked: currentValue != null,
      active: true,
      lots: [],
      netQuantity,
      remainingCostBasis: "100.00",
      realizedPnl: "0.00",
      closed: false,
      createdAt: "2026-01-01T00:00:00Z",
    },
    currentPrice: currentValue,
    priceCurrency: "EUR",
    priceAsOf: "2026-07-29",
    priceObservedAt: "2026-07-29T09:00:00Z",
    stale: false,
    currentValue,
    unrealizedPnl: null,
    unrealizedPnlPct: null,
    realizedPnl: "0.00",
    soldCostBasis: "0.00",
    totalReturn: null,
    weight: null,
  };
}

const symbols = (rows: HoldingSummary[]) => rows.map((r) => r.holding.symbol);

describe("nextSort", () => {
  it("opens a money column on the biggest value and text A→Z", () => {
    expect(nextSort(null, "currentValue")).toEqual({ key: "currentValue", direction: "desc" });
    expect(nextSort(null, "symbol")).toEqual({ key: "symbol", direction: "asc" });
  });

  it("flips the column already sorted", () => {
    expect(nextSort({ key: "currentValue", direction: "desc" }, "currentValue")).toEqual({
      key: "currentValue",
      direction: "asc",
    });
  });

  it("starts a different column fresh rather than carrying the direction over", () => {
    expect(nextSort({ key: "symbol", direction: "desc" }, "currentValue")).toEqual({
      key: "currentValue",
      direction: "desc",
    });
  });
});

describe("sortHoldings", () => {
  const rows = [row("BTC", { currentValue: "300.00" }), row("ETH", { currentValue: "900.00" }), row("ADA", { currentValue: "600.00" })];
  const noWeights = new Map<string, string>();

  it("leaves the server's order alone until a column is picked", () => {
    expect(symbols(sortHoldings(rows, null, noWeights, "en"))).toEqual(["BTC", "ETH", "ADA"]);
  });

  it("orders a money column both ways", () => {
    expect(symbols(sortHoldings(rows, { key: "currentValue", direction: "desc" }, noWeights, "en"))).toEqual(["ETH", "ADA", "BTC"]);
    expect(symbols(sortHoldings(rows, { key: "currentValue", direction: "asc" }, noWeights, "en"))).toEqual(["BTC", "ADA", "ETH"]);
  });

  it("compares numbers as numbers, not as the strings the API sends", () => {
    const wide = [row("SMALL", { netQuantity: "9" }), row("BIG", { netQuantity: "10" })];
    expect(symbols(sortHoldings(wide, { key: "quantity", direction: "desc" }, noWeights, "en"))).toEqual(["BIG", "SMALL"]);
  });

  it("sorts by the weight it was handed, not by anything on the row", () => {
    const weights = new Map([["BTC", "0.6"], ["ETH", "0.1"], ["ADA", "0.3"]]);
    expect(symbols(sortHoldings(rows, { key: "weight", direction: "desc" }, weights, "en"))).toEqual(["BTC", "ADA", "ETH"]);
  });

  it("sinks an unpriced holding to the bottom in either direction — no price is not a small price", () => {
    const withUnpriced = [row("FUND", { currentValue: null }), ...rows];
    expect(symbols(sortHoldings(withUnpriced, { key: "currentValue", direction: "desc" }, noWeights, "en")).at(-1)).toBe("FUND");
    expect(symbols(sortHoldings(withUnpriced, { key: "currentValue", direction: "asc" }, noWeights, "en")).at(-1)).toBe("FUND");
  });

  it("breaks ties on the symbol, so equal rows never swap places between renders", () => {
    const tied = [row("ZEC", { currentValue: "100.00" }), row("ADA", { currentValue: "100.00" })];
    expect(symbols(sortHoldings(tied, { key: "currentValue", direction: "desc" }, noWeights, "en"))).toEqual(["ADA", "ZEC"]);
  });

  it("sorts symbols alphabetically", () => {
    expect(symbols(sortHoldings(rows, { key: "symbol", direction: "asc" }, noWeights, "en"))).toEqual(["ADA", "BTC", "ETH"]);
    expect(symbols(sortHoldings(rows, { key: "symbol", direction: "desc" }, noWeights, "en"))).toEqual(["ETH", "BTC", "ADA"]);
  });
});
