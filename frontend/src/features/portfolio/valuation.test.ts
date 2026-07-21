import { describe, expect, it } from "vitest";
import { fractionOf, fractionToPercent, percentLabel, pnlTone, returnPercents, signedMoney } from "./valuation";

describe("fractionToPercent", () => {
  it("converts backend fractions to percent numbers", () => {
    expect(fractionToPercent("0.1663")).toBeCloseTo(16.63);
    expect(fractionToPercent("-0.05")).toBeCloseTo(-5);
    expect(fractionToPercent("0")).toBe(0);
  });

  it("returns null for missing or malformed values", () => {
    expect(fractionToPercent(null)).toBeNull();
    expect(fractionToPercent(undefined)).toBeNull();
    expect(fractionToPercent("")).toBeNull();
    expect(fractionToPercent("abc")).toBeNull();
  });
});

describe("pnlTone", () => {
  it("classifies gains, losses and zero", () => {
    expect(pnlTone("123.45")).toBe("positive");
    expect(pnlTone("-0.01")).toBe("negative");
    expect(pnlTone("0")).toBe("neutral");
    expect(pnlTone(null)).toBe("neutral");
  });
});

describe("fractionOf", () => {
  it("divides into a backend-convention fraction string", () => {
    expect(fractionOf("5000", "10000")).toBe("0.5");
    // The sell-and-rebuy total return: (5000 + 3000) / (15000 + 10000).
    expect(fractionOf("8000", "25000")).toBe("0.32");
    expect(fractionOf("-50", "200")).toBe("-0.25");
  });

  it("returns null for zero or missing inputs — never a fake 0", () => {
    expect(fractionOf("100", "0")).toBeNull();
    expect(fractionOf("100", null)).toBeNull();
    expect(fractionOf(null, "100")).toBeNull();
    expect(fractionOf("100", "abc")).toBeNull();
  });
});

describe("percentLabel", () => {
  it("formats a backend fraction at one decimal", () => {
    expect(percentLabel("0.5", "en")).toBe("50.0%");
    expect(percentLabel("0.1663", "en")).toBe("16.6%");
  });

  it("adds a plus only when signed and positive", () => {
    expect(percentLabel("0.5", "en", true)).toBe("+50.0%");
    expect(percentLabel("-0.25", "en", true)).toBe("-25.0%");
    expect(percentLabel("0", "en", true)).toBe("0.0%");
  });

  it("renders an em dash when the fraction is undefined", () => {
    expect(percentLabel(null, "en")).toBe("—");
    expect(percentLabel(undefined, "en")).toBe("—");
  });
});

describe("returnPercents", () => {
  // The audited crypto-only household: hundreds of BTC/FET/SUI sell-and-rebuy cycles pile up
  // ~€1.6M of cumulative sold-lot cost, an order of magnitude over the €138k still invested.
  // Euro amounts are correct and identical across modes; only the % denominators differ.
  const churn = {
    totalUnrealizedPnl: "-103766.94",
    totalRealizedPnl: "75641.09",
    totalReturn: "-28125.85", // = unrealized (-103766.94) + realized (75641.09)
    totalCostBasis: "138464.21", // open-lots cost
    totalSoldCostBasis: "1626000.00", // cumulative FIFO cost of every sold lot (churn-inflated)
  };

  it("OPEN_COST bases realized and total on the open cost, keeping them legible", () => {
    const rp = returnPercents("OPEN_COST", churn);
    expect(rp.realizedBasis).toBe("138464.21");
    expect(rp.totalBasis).toBe("138464.21");
    expect(fractionToPercent(rp.realizedPnlPct)).toBeCloseTo(54.63, 1);
    expect(fractionToPercent(rp.totalReturnPct)).toBeCloseTo(-20.31, 1);
  });

  it("OPEN_COST makes the percentages additive like the euros (unrealized + realized = total)", () => {
    const rp = returnPercents("OPEN_COST", churn);
    const unrealizedPct = fractionToPercent(fractionOf("-103766.94", churn.totalCostBasis))!;
    const realizedPct = fractionToPercent(rp.realizedPnlPct)!;
    const totalPct = fractionToPercent(rp.totalReturnPct)!;
    expect(unrealizedPct + realizedPct).toBeCloseTo(totalPct, 2);
  });

  it("TURNOVER reproduces the churn-inflated denominators (the pre-existing behavior)", () => {
    const rp = returnPercents("TURNOVER", churn);
    expect(rp.realizedBasis).toBe("1626000.00"); // cost of all sold lots
    expect(rp.totalBasis).toBe("1764464.21"); // open + sold
    expect(fractionToPercent(rp.realizedPnlPct)).toBeCloseTo(4.65, 1);
    expect(fractionToPercent(rp.totalReturnPct)).toBeCloseTo(-1.59, 1);
  });

  it("keeps a null total return null and never fakes a 0% from a zero base", () => {
    expect(returnPercents("OPEN_COST", { ...churn, totalReturn: null }).totalReturnPct).toBeNull();
    expect(returnPercents("OPEN_COST", { ...churn, totalCostBasis: "0" }).realizedPnlPct).toBeNull();
    expect(returnPercents("TURNOVER", { ...churn, totalSoldCostBasis: "0" }).realizedPnlPct).toBeNull();
  });

  it("NET_INVESTED nets out recycled capital: base = open cost − realized (churn portfolio)", () => {
    const rp = returnPercents("NET_INVESTED", churn);
    expect(rp.houseMoney).toBe(false);
    // 138464.21 − 75641.09 = 62823.12, shared by all three percentages.
    expect(rp.unrealizedBasis).toBe("62823.12");
    expect(rp.realizedBasis).toBe("62823.12");
    expect(rp.totalBasis).toBe("62823.12");
    expect(fractionToPercent(rp.totalReturnPct)).toBeCloseTo(-44.77, 1); // shown as -44.8%
  });

  it("NET_INVESTED keeps all three additive on the one base", () => {
    const rp = returnPercents("NET_INVESTED", churn);
    const u = fractionToPercent(rp.unrealizedPnlPct)!;
    const r = fractionToPercent(rp.realizedPnlPct)!;
    const total = fractionToPercent(rp.totalReturnPct)!;
    expect(u + r).toBeCloseTo(total, 2);
  });

  it("NET_INVESTED on a buy-and-hold portfolio", () => {
    // open cost 197479.81, realized +25757.62 → net invested 171722.19; total +19936.61 → +11.6%
    const buyHold = {
      totalUnrealizedPnl: "-5821.01", // total − realized
      totalRealizedPnl: "25757.62",
      totalReturn: "19936.61",
      totalCostBasis: "197479.81",
      totalSoldCostBasis: "45104.48", // unused by NET_INVESTED
    };
    const rp = returnPercents("NET_INVESTED", buyHold);
    expect(rp.houseMoney).toBe(false);
    expect(rp.totalBasis).toBe("171722.19");
    expect(fractionToPercent(rp.totalReturnPct)).toBeCloseTo(11.61, 1); // shown as +11.6%
  });

  it("NET_INVESTED shows house money (—) when realized exceeds open cost", () => {
    const houseMoney = {
      totalUnrealizedPnl: "1000.00",
      totalRealizedPnl: "60000.00",
      totalReturn: "61000.00",
      totalCostBasis: "50000.00", // net invested = 50000 − 60000 = −10000 (≤ 0)
      totalSoldCostBasis: "80000.00",
    };
    const rp = returnPercents("NET_INVESTED", houseMoney);
    expect(rp.houseMoney).toBe(true);
    expect(rp.unrealizedPnlPct).toBeNull();
    expect(rp.realizedPnlPct).toBeNull();
    expect(rp.totalReturnPct).toBeNull();
  });

  it("NET_INVESTED treats an exactly-zero base as house money too", () => {
    const rp = returnPercents("NET_INVESTED", { ...churn, totalCostBasis: "75641.09" }); // net = 0
    expect(rp.houseMoney).toBe(true);
    expect(rp.totalReturnPct).toBeNull();
  });

  it("exposes net invested (open cost − realized) identically in every mode", () => {
    // 138464.21 − 75641.09 = 62823.12 — the always-on 'Own money' figure, not basis-dependent.
    for (const b of ["OPEN_COST", "NET_INVESTED", "TURNOVER"] as const) {
      const rp = returnPercents(b, churn);
      expect(rp.netInvested).toBe("62823.12");
      expect(rp.netInvestedHouseMoney).toBe(false);
    }
    // Buy-and-hold: 197479.81 − 25757.62 = 171722.19.
    const buyHold = {
      totalUnrealizedPnl: "-5821.01",
      totalRealizedPnl: "25757.62",
      totalReturn: "19936.61",
      totalCostBasis: "197479.81",
      totalSoldCostBasis: "45104.48",
    };
    expect(returnPercents("OPEN_COST", buyHold).netInvested).toBe("171722.19");
  });

  it("flags net-invested house money in every mode, but only NET_INVESTED blanks its percentages", () => {
    const houseMoney = {
      totalUnrealizedPnl: "1000.00",
      totalRealizedPnl: "60000.00",
      totalReturn: "61000.00",
      totalCostBasis: "50000.00", // net invested = 50000 − 60000 = −10000
      totalSoldCostBasis: "80000.00",
    };
    for (const b of ["OPEN_COST", "NET_INVESTED", "TURNOVER"] as const) {
      const rp = returnPercents(b, houseMoney);
      expect(rp.netInvested).toBe("-10000");
      expect(rp.netInvestedHouseMoney).toBe(true);
    }
    // netInvestedHouseMoney is a fact for the 'Own money' line; only NET_INVESTED nulls its %.
    expect(returnPercents("OPEN_COST", houseMoney).houseMoney).toBe(false);
    expect(returnPercents("OPEN_COST", houseMoney).totalReturnPct).not.toBeNull();
    expect(returnPercents("NET_INVESTED", houseMoney).houseMoney).toBe(true);
  });
});

describe("signedMoney", () => {
  it("prefixes gains with a plus and leaves losses alone", () => {
    expect(signedMoney("10", "€10.00")).toBe("+€10.00");
    expect(signedMoney("-10", "-€10.00")).toBe("-€10.00");
    expect(signedMoney("0", "€0.00")).toBe("€0.00");
  });
});
