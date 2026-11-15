import { describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { HoldingSummary } from "@/api/portfolio";
import {
  applyPctShortcut,
  describeCadence,
  formatAlertTimestamp,
  inferAlertDirection,
  isAlertEligibleHolding,
  isoDateInZone,
} from "./priceAlerts";

function makeSummary(over: Partial<HoldingSummary["holding"]> = {}): HoldingSummary {
  return {
    holding: {
      id: "h1",
      assetClass: "crypto",
      symbol: "SUI",
      label: null,
      nativeCurrency: "USD",
      isin: null,
      provider: "coingecko",
      providerSymbol: "sui",
      linked: true,
      providerNotFoundSince: null,
      active: true,
      lots: [],
      netQuantity: "10",
      remainingCostBasis: "20.00",
      realizedPnl: "0.00",
      closed: false,
      createdAt: "2026-01-01T00:00:00Z",
      ...over,
    },
    currentPrice: "2.10",
    priceCurrency: "USD",
    priceAsOf: "2026-07-29",
    priceObservedAt: "2026-07-29T09:00:00Z",
    stale: false,
    currentValue: "19.50",
    unrealizedPnl: "-0.50",
    unrealizedPnlPct: null,
    realizedPnl: "0.00",
    soldCostBasis: "0.00",
    totalReturn: "-0.50",
    weight: null,
  };
}

describe("priceAlerts helpers", () => {
  it("infers direction and flags equal or non-positive targets", () => {
    expect(inferAlertDirection("2.80", "2.10")).toBe("ABOVE");
    expect(inferAlertDirection("1.50", "2.10")).toBe("BELOW");
    expect(inferAlertDirection("2.1000", "2.1")).toBe("EQUAL");
    expect(inferAlertDirection("0", "2.10")).toBe("INVALID");
    expect(inferAlertDirection("-5", "2.10")).toBe("INVALID");
    expect(inferAlertDirection("abc", "2.10")).toBe("INVALID");
    expect(inferAlertDirection("", "2.10")).toBeNull();
  });

  it("computes percentage shortcuts preserving useful precision on small coins", () => {
    expect(applyPctShortcut("2.00", 10)).toBe("2.2");
    expect(applyPctShortcut("2.00", -15)).toBe("1.7");
    expect(applyPctShortcut("0.00012", 5)).toBe("0.000126");
    expect(applyPctShortcut("2.00", -100)).toBeNull();
  });

  it("describes scheduler cadences in EN and ES", async () => {
    const t = i18n.t.bind(i18n);
    expect(describeCadence({ unit: "HOURS", interval: 1, weekdaysOnly: false, dailyTime: null, includesNightlyClose: false }, t))
      .toContain("every hour");
    expect(describeCadence({ unit: "HOURS", interval: 2, weekdaysOnly: true, dailyTime: "22:10", includesNightlyClose: true }, t))
      .toContain("every 2 hours on weekdays");
    expect(describeCadence({ unit: "DAYS", interval: 1, weekdaysOnly: false, dailyTime: "22:10", includesNightlyClose: false }, t))
      .toContain("22:10");

    await i18n.changeLanguage("es");
    expect(describeCadence({ unit: "HOURS", interval: 1, weekdaysOnly: false, dailyTime: null, includesNightlyClose: false }, t))
      .toContain("cada hora");
    await i18n.changeLanguage("en");
  });

  it("formats timestamps and extracts YYYY-MM-DD in the display timezone", () => {
    // 23:30 UTC is 01:30 next day in Europe/Madrid (UTC+2 in summer)
    expect(isoDateInZone("2026-07-29T23:30:00Z", "Europe/Madrid")).toBe("2026-07-30");
    const formatted = formatAlertTimestamp("2026-07-29T23:30:00Z", "en", "Europe/Madrid");
    expect(formatted).toContain("30");
    expect(formatted).toContain("2026");
    expect(formatted).toContain("01:30");
  });

  it("identifies open linked non-fund holdings as alert-eligible", () => {
    expect(isAlertEligibleHolding(makeSummary())).toBe(true);
    expect(isAlertEligibleHolding(makeSummary({ closed: true, netQuantity: "0" }))).toBe(false);
    expect(isAlertEligibleHolding(makeSummary({ linked: false }))).toBe(false);
    expect(isAlertEligibleHolding(makeSummary({ assetClass: "fund" }))).toBe(false);
  });
});
