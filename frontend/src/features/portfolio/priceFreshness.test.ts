import { describe, expect, it } from "vitest";
import type { HoldingAssetClass, HoldingSummary } from "@/api/portfolio";
import {
  CRYPTO_STALE_AFTER_HOURS,
  EQUITY_STALE_AFTER_DAYS,
  formatPriceAge,
  oldestStalePriceAge,
  priceAge,
  priceAsOfLabel,
} from "./priceFreshness";

const NOW = new Date("2026-07-29T10:00:00Z"); // a Wednesday

function row(
  assetClass: HoldingAssetClass,
  over: Partial<HoldingSummary> & { closed?: boolean } = {},
): HoldingSummary {
  const { closed = false, ...fields } = over;
  return {
    holding: {
      id: `${assetClass}-${closed}`,
      assetClass,
      symbol: assetClass.toUpperCase(),
      label: null,
      nativeCurrency: "EUR",
      isin: null,
      provider: null,
      providerSymbol: null,
      linked: true,
      active: true,
      lots: [],
      netQuantity: closed ? "0" : "1",
      remainingCostBasis: "100.00",
      realizedPnl: "0.00",
      closed,
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
    ...fields,
  };
}

describe("priceAge for crypto (intraday cadence)", () => {
  it("dates the price from the observation instant, not the price date", () => {
    const age = priceAge(row("crypto", { priceObservedAt: "2026-07-29T08:00:00Z" }), NOW);
    expect(age?.ageMs).toBe(2 * 60 * 60 * 1000);
    expect(age?.dayGranular).toBe(false);
    expect(age?.stale).toBe(false);
  });

  it(`flags only past ${CRYPTO_STALE_AFTER_HOURS}h — a missed hourly run, not a normal one`, () => {
    const withinTolerance = priceAge(row("crypto", { priceObservedAt: "2026-07-29T07:00:00Z" }), NOW);
    const beyondTolerance = priceAge(row("crypto", { priceObservedAt: "2026-07-29T06:30:00Z" }), NOW);
    expect(withinTolerance?.stale).toBe(false); // exactly 3h
    expect(beyondTolerance?.stale).toBe(true); // 3h30
  });

  it("clamps a server clock ahead of the browser to zero instead of a future price", () => {
    expect(priceAge(row("crypto", { priceObservedAt: "2026-07-29T10:05:00Z" }), NOW)?.ageMs).toBe(0);
  });

  it("has no age without an observation instant", () => {
    expect(priceAge(row("crypto", { priceObservedAt: null }), NOW)).toBeNull();
  });
});

describe("priceAge for equities (one close per trading day)", () => {
  it("counts calendar days from the trading day", () => {
    const age = priceAge(row("etf", { priceAsOf: "2026-07-26" }), NOW);
    expect(age?.ageMs).toBe(3 * 24 * 60 * 60 * 1000);
    expect(age?.dayGranular).toBe(true);
    expect(age?.asOf).toBe("2026-07-26");
  });

  it("does not flag a Friday close seen on Monday morning", () => {
    const monday = new Date("2026-08-03T08:00:00Z");
    const friday = priceAge(row("stock", { priceAsOf: "2026-07-31" }), monday);
    expect(friday?.ageMs).toBe(3 * 24 * 60 * 60 * 1000);
    expect(friday?.stale).toBe(false);
  });

  it("does not flag a Friday close after a Monday holiday either", () => {
    const tuesday = new Date("2026-08-04T08:00:00Z");
    expect(priceAge(row("stock", { priceAsOf: "2026-07-31" }), tuesday)?.stale).toBe(false); // 4 days
  });

  it(`flags past ${EQUITY_STALE_AFTER_DAYS} calendar days — a genuinely missed refresh`, () => {
    const wednesday = new Date("2026-08-05T08:00:00Z");
    expect(priceAge(row("stock", { priceAsOf: "2026-07-31" }), wednesday)?.stale).toBe(true); // 5 days
  });

  it("ignores the observation instant, which lags the close by a night", () => {
    // Fetched by the nightly job hours ago, but the price itself is Friday's close.
    const monday = new Date("2026-08-03T08:00:00Z");
    const age = priceAge(row("etf", { priceAsOf: "2026-07-31", priceObservedAt: "2026-08-03T01:00:00Z" }), monday);
    expect(age?.ageMs).toBe(3 * 24 * 60 * 60 * 1000);
  });
});

describe("priceAge exclusions", () => {
  it("has no age for a closed position — its value is zero at any price", () => {
    expect(priceAge(row("crypto", { closed: true }), NOW)).toBeNull();
  });

  it("has no age for a fund: no provider, value entered by hand in a snapshot", () => {
    expect(priceAge(row("fund", { currentPrice: null, priceAsOf: null, priceObservedAt: null }), NOW)).toBeNull();
    // Even if a price somehow existed, a fund's freshness is not something we track.
    expect(priceAge(row("fund"), NOW)).toBeNull();
  });

  it("has no age for an unpriced holding", () => {
    expect(priceAge(row("etf", { currentPrice: null }), NOW)).toBeNull();
  });
});

describe("oldestStalePriceAge", () => {
  it("picks the worst age among the stale holdings", () => {
    const oldest = oldestStalePriceAge(
      [
        row("crypto", { priceObservedAt: "2026-07-29T04:00:00Z" }), // 6h — stale
        row("etf", { priceAsOf: "2026-07-24" }), // 5 days — stale
        row("stock", { priceAsOf: "2026-07-20" }), // 9 days — stale, the worst
      ],
      NOW,
    );
    expect(oldest?.asOf).toBe("2026-07-20");
  });

  it("is null when every price is within its tolerance — no summary line to show", () => {
    const fresh = [
      row("crypto", { priceObservedAt: "2026-07-29T09:30:00Z" }), // 30m
      row("etf", { priceAsOf: "2026-07-27" }), // 2 days
    ];
    expect(oldestStalePriceAge(fresh, NOW)).toBeNull();
  });

  it("never lets a fresher-but-stale row lose to an older fresh one", () => {
    const oldest = oldestStalePriceAge(
      [
        row("etf", { priceAsOf: "2026-07-26" }), // 3 days, within tolerance
        row("crypto", { priceObservedAt: "2026-07-29T05:00:00Z" }), // 5h, stale
      ],
      NOW,
    );
    expect(oldest?.asOf).toBe("2026-07-29T05:00:00Z");
  });

  it("ignores rows that carry no age, so closed and unpriced holdings can't win", () => {
    const oldest = oldestStalePriceAge(
      [
        row("etf", { priceAsOf: "2026-01-01", currentPrice: null }),
        row("crypto", { closed: true, priceObservedAt: "2025-01-01T00:00:00Z" }),
        row("crypto", { priceObservedAt: "2026-07-29T05:00:00Z" }),
      ],
      NOW,
    );
    expect(oldest?.asOf).toBe("2026-07-29T05:00:00Z");
  });

  it("is null when nothing has a price", () => {
    expect(oldestStalePriceAge([row("fund", { currentPrice: null })], NOW)).toBeNull();
    expect(oldestStalePriceAge([], NOW)).toBeNull();
  });
});

describe("priceAsOfLabel", () => {
  it("dates a daily close by its trading day alone", () => {
    const age = priceAge(row("etf", { priceAsOf: "2026-07-27" }), NOW)!;
    expect(priceAsOfLabel(age, "en")).toBe("Jul 27, 2026");
    expect(priceAsOfLabel(age, "es")).toBe("27 jul 2026");
  });

  it("adds the clock time for an intraday price, the only place it is shown", () => {
    const age = priceAge(row("crypto", { priceObservedAt: "2026-07-29T08:00:00Z" }), NOW)!;
    expect(priceAsOfLabel(age, "en")).toMatch(/^Jul 29, 2026, \d{1,2}:\d{2} (AM|PM)$/);
    expect(priceAsOfLabel(age, "es")).toMatch(/^29 jul 2026, \d{2}:\d{2}$/);
  });
});

describe("formatPriceAge", () => {
  const crypto = (observedAt: string) => priceAge(row("crypto", { priceObservedAt: observedAt }), NOW)!;
  const equity = (priceAsOf: string) => priceAge(row("etf", { priceAsOf }), NOW)!;

  it("formats hours and days compactly in English", () => {
    expect(formatPriceAge(crypto("2026-07-29T08:00:00Z"), "en")).toBe("2h ago");
    expect(formatPriceAge(equity("2026-07-26"), "en")).toBe("3d ago");
  });

  it("formats in Spanish", () => {
    expect(formatPriceAge(crypto("2026-07-29T08:00:00Z"), "es")).toBe("hace 2 h");
    expect(formatPriceAge(equity("2026-07-26"), "es")).toBe("hace 3 d");
  });

  it("uses minutes below the hour, never a bare zero", () => {
    expect(formatPriceAge(crypto("2026-07-29T09:15:00Z"), "en")).toBe("45m ago");
    expect(formatPriceAge(crypto("2026-07-29T09:59:50Z"), "en")).toBe("1m ago");
  });

  it("rolls a stale crypto price over into days", () => {
    expect(formatPriceAge(crypto("2026-07-27T10:00:00Z"), "en")).toBe("2d ago");
  });

  it("says today for a same-day close instead of 0d ago", () => {
    expect(formatPriceAge(equity("2026-07-29"), "en")).toBe("today");
    expect(formatPriceAge(equity("2026-07-29"), "es")).toBe("hoy");
  });
});
