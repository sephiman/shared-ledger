import { describe, expect, it } from "vitest";
import type { HoldingSummary } from "@/api/portfolio";
import { DUST_HOLDING_MAX_VALUE_EUR, isDustHolding, partitionDust } from "./dustHoldings";

function row(id: string, over: Partial<HoldingSummary> = {}): HoldingSummary {
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
      linked: true,
      active: true,
      lots: [],
      netQuantity: "1",
      remainingCostBasis: "100.00",
      realizedPnl: "0.00",
      closed: false,
      createdAt: "2026-01-01T00:00:00Z",
    },
    currentPrice: "100",
    priceCurrency: "EUR",
    priceAsOf: "2026-07-29",
    priceObservedAt: "2026-07-29T09:00:00Z",
    stale: false,
    currentValue: "100.00",
    unrealizedPnl: "0.00",
    unrealizedPnlPct: "0",
    realizedPnl: "0.00",
    soldCostBasis: "0.00",
    totalReturn: "0.00",
    weight: "1",
    ...over,
  };
}

describe("isDustHolding", () => {
  it("hides a fully closed position — the backend values it at exactly zero", () => {
    const closed = row("sold", { currentValue: "0.00", holding: { ...row("sold").holding, netQuantity: "0", closed: true } });
    expect(isDustHolding(closed)).toBe(true);
  });

  it("hides a rounding remnant worth a fraction of a cent", () => {
    expect(isDustHolding(row("dust", { currentValue: "0.0000000004" }))).toBe(true);
  });

  it("keeps a tiny quantity of an expensive asset — the criterion is money, not quantity", () => {
    const wholeCoin = row("btc", {
      currentValue: "5000.00",
      holding: { ...row("btc").holding, netQuantity: "0.00000008" },
    });
    expect(isDustHolding(wholeCoin)).toBe(false);
  });

  it(`draws the line strictly below ${DUST_HOLDING_MAX_VALUE_EUR}`, () => {
    expect(isDustHolding(row("under", { currentValue: "0.99" }))).toBe(true);
    expect(isDustHolding(row("at", { currentValue: "1.00" }))).toBe(false);
    expect(isDustHolding(row("over", { currentValue: "1.01" }))).toBe(false);
  });

  it("never hides an unpriced open holding — its value is unknown, not small", () => {
    expect(isDustHolding(row("unlinked", { currentValue: null, currentPrice: null }))).toBe(false);
  });

  it("keeps a row whose value cannot be parsed rather than hiding it on a guess", () => {
    expect(isDustHolding(row("broken", { currentValue: "not-a-number" }))).toBe(false);
  });
});

describe("partitionDust", () => {
  it("splits the rows and counts what is hidden, preserving order", () => {
    const rows = [
      row("a", { currentValue: "100.00" }),
      row("b", { currentValue: "0.00" }),
      row("c", { currentValue: "50.00" }),
      row("d", { currentValue: "0.004" }),
    ];
    const { visible, hiddenCount } = partitionDust(rows);
    expect(visible.map((r) => r.holding.id)).toEqual(["a", "c"]);
    expect(hiddenCount).toBe(2);
  });

  it("hides nothing when every holding is worth something", () => {
    expect(partitionDust([row("a"), row("b")]).hiddenCount).toBe(0);
  });
});
