import { describe, expect, it } from "vitest";
import type { HoldingSummary } from "@/api/portfolio";
import { weightsWithin } from "./holdingWeights";

function row(id: string, currentValue: string | null): HoldingSummary {
  return {
    holding: {
      id,
      assetClass: "crypto",
      symbol: id.toUpperCase(),
      label: null,
      nativeCurrency: "EUR",
      isin: null,
      provider: null,
      providerSymbol: null,
      linked: currentValue != null,
      active: true,
      lots: [],
      netQuantity: "1",
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

describe("weightsWithin", () => {
  it("gives each holding its share of the priced total", () => {
    const weights = weightsWithin([row("a", "750.00"), row("b", "250.00")]);
    expect(weights.get("a")).toBe("0.75");
    expect(weights.get("b")).toBe("0.25");
  });

  it("re-bases on the rows it is given, so a filtered subset still adds up to 100 %", () => {
    const all = [row("a", "800.00"), row("b", "200.00")];
    expect(weightsWithin(all).get("b")).toBe("0.2");
    expect(weightsWithin([all[1]]).get("b")).toBe("1");
  });

  it("leaves an unpriced holding out, and out of the denominator", () => {
    const weights = weightsWithin([row("a", "300.00"), row("fund", null)]);
    expect(weights.has("fund")).toBe(false);
    expect(weights.get("a")).toBe("1");
  });

  it("gives no weight at all when nothing is worth anything — a share of nothing is not 0 %", () => {
    expect(weightsWithin([row("a", "0.00"), row("b", "0.00")])).toEqual(new Map());
  });

  it("rounds to the backend's scale-4 fraction", () => {
    const weights = weightsWithin([row("a", "1"), row("b", "2")]);
    expect(weights.get("a")).toBe("0.3333");
    expect(weights.get("b")).toBe("0.6667");
  });
});
