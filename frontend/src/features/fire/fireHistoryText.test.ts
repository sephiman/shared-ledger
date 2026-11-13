import { beforeEach, describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { FireAttribution, FireHistory } from "@/api/fire";
import { attributionSentence, historySummary } from "./fireHistoryText";

const attribution: FireAttribution = {
  fromDate: "2025-11-10",
  toDate: "2026-11-13",
  merged: false,
  totalPoints: "3.0",
  contributedPoints: "2.1",
  marketPoints: "1.4",
  wealthPoints: "3.5",
  needPoints: "-0.5",
  wealthFrom: "100000.00",
  wealthTo: "130000.00",
  targetFrom: "700000.00",
  targetTo: "760000.00",
  contributedAmount: "18000.00",
  marketAmount: "12000.00",
};

function history(over: Partial<FireHistory> = {}): FireHistory {
  return {
    tier: "fire",
    horizonYear: 2050,
    points: [
      { date: "2025-11-10", snapshotDate: "2025-11-10", current: false, coveragePercent: 14.2, estimatedYear: 2049, probability: 0.52, wealth: "100000.00", target: "700000.00" },
      { date: "2026-11-13", snapshotDate: "2026-10-19", current: true, coveragePercent: 17.1, estimatedYear: 2046, probability: 0.61, wealth: "130000.00", target: "760000.00" },
    ],
    omitted: [],
    manualContribution: true,
    manualSpending: false,
    attribution,
    ...over,
  };
}

describe("history and attribution wording", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("summarises a year ago against today", () => {
    expect(historySummary(history(), "en", i18n.t)).toBe("A year ago: 14% · 2049. Today: 17% · 2046.");
  });

  it("names the date when the closest point is not about a year back, and says beyond the horizon", () => {
    const h = history();
    h.points[0] = { ...h.points[0], date: "2026-04-02", estimatedYear: null };
    h.attribution = { ...attribution, fromDate: "2026-04-02" };
    expect(historySummary(h, "en", i18n.t)).toBe("On 2 Apr 2026: 14% · Beyond 2050. Today: 17% · 2046.");
  });

  it("with only today there is nothing to compare with", () => {
    const h = history({ attribution: null });
    h.points = [h.points[1]];
    expect(historySummary(h, "en", i18n.t)).toBe("Today: 17% · 2046. There is no earlier point to compare with yet.");
  });

  it("explains the change in one sentence whose parts add up", () => {
    expect(attributionSentence(attribution, "en", i18n.t)).toBe(
      "You gained 3.0 points: +2.1 from what you put in, +1.4 from the market, −0.5 because you need more.",
    );
  });

  it("merges contributions and market when they cannot be told apart", () => {
    const merged = { ...attribution, merged: true, contributedPoints: null, marketPoints: null, needPoints: "0.4", wealthPoints: "-1.6", totalPoints: "-1.2" };
    expect(attributionSentence(merged, "en", i18n.t)).toBe(
      "You lost 1.2 points: −1.6 from the change in your wealth, +0.4 because you need less.",
    );
  });

  it("reads in Spanish", async () => {
    await i18n.changeLanguage("es");
    expect(historySummary(history(), "es", i18n.t)).toBe("Hace un año: 14 % · 2049. Hoy: 17 % · 2046.");
  });
});
