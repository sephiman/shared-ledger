import { describe, expect, it } from "vitest";
import { computeCurrentWealth, type CurrentWealthInput } from "./currentWealth";
import type { Asset, Liability, Snapshot } from "@/api/networth";

const ASSET_CLASSES = ["cash", "fund", "etfs", "stocks", "crypto", "pension"].map((code, i) => ({
  code,
  sortOrder: (i + 1) * 10,
}));

function namedAsset(id: string, overrides: Partial<Asset> = {}): Asset {
  return { id, name: id, type: "property", active: true, latestValue: null, latestValueDate: null, ...overrides };
}

function liability(id: string, overrides: Partial<Liability> = {}): Liability {
  return { id, name: id, active: true, amortizable: false, latestBalance: null, latestBalanceDate: null, ...overrides };
}

function previousSnapshot(overrides: Partial<Snapshot> = {}): Snapshot {
  return {
    id: "snap-1",
    snapshotDate: "2026-06-01",
    note: null,
    totalAssets: "0",
    totalLiabilities: "0",
    netWorth: "0",
    assets: [],
    namedAssets: [],
    liabilities: [],
    createdAt: "2026-06-01T00:00:00Z",
    ...overrides,
  };
}

function input(overrides: Partial<CurrentWealthInput> = {}): CurrentWealthInput {
  return {
    assetClasses: ASSET_CLASSES,
    prefill: { previous: null, activeLiabilities: [] },
    namedAssets: [],
    liabilities: [],
    portfolioByClass: {},
    namedValues: { assets: {}, liabilities: {}, cash: null },
    ...overrides,
  };
}

describe("computeCurrentWealth", () => {
  it("groups live values into cash, portfolio, pension, named assets and liabilities", () => {
    const w = computeCurrentWealth(
      input({
        prefill: {
          previous: previousSnapshot({
            assets: [
              { assetClassCode: "cash", value: "1000.00" },
              { assetClassCode: "pension", value: "5000.00" },
            ],
          }),
          activeLiabilities: [],
        },
        portfolioByClass: { crypto: "200.00", etfs: "300.00", stocks: "400.00", fund: "100.00" },
        namedAssets: [namedAsset("home", { latestValue: "250000.00" })],
        liabilities: [liability("mortgage", { latestBalance: "180000.00" })],
        namedValues: { assets: {}, liabilities: {}, cash: "1500.00" },
      }),
    );
    expect(w.cash.toFixed(2)).toBe("1500.00");
    expect(w.portfolio.toFixed(2)).toBe("1000.00");
    expect(w.pension.toFixed(2)).toBe("5000.00");
    expect(w.namedAssets.toFixed(2)).toBe("250000.00");
    expect(w.liabilities.toFixed(2)).toBe("180000.00");
    expect(w.netWorth.toFixed(2)).toBe("77500.00");
    expect(w.isEmpty).toBe(false);
  });

  it("reconciles: cash + portfolio + pension + named assets − liabilities = net worth", () => {
    const w = computeCurrentWealth(
      input({
        prefill: {
          previous: previousSnapshot({
            assets: [
              { assetClassCode: "cash", value: "123.45" },
              { assetClassCode: "fund", value: "678.90" },
              { assetClassCode: "pension", value: "42.42" },
            ],
          }),
          activeLiabilities: [],
        },
        portfolioByClass: { stocks: "999.99" },
        namedAssets: [namedAsset("car", { latestValue: "3000.00" })],
        liabilities: [liability("loan", { latestBalance: "1234.56" })],
      }),
    );
    const sum = w.cash.plus(w.portfolio).plus(w.pension).plus(w.namedAssets).minus(w.liabilities);
    expect(sum.eq(w.netWorth)).toBe(true);
  });

  it("prefers today's portfolio valuation over the carried-over class value, class by class", () => {
    const w = computeCurrentWealth(
      input({
        prefill: {
          previous: previousSnapshot({
            assets: [
              { assetClassCode: "crypto", value: "100.00" },
              { assetClassCode: "fund", value: "5000.00" },
            ],
          }),
          activeLiabilities: [],
        },
        // No fund holdings: the manually-entered fund value must carry over.
        portfolioByClass: { crypto: "150.00" },
      }),
    );
    expect(w.portfolio.toFixed(2)).toBe("5150.00");
  });

  it("uses the cash estimate when present and carries cash over when there is none", () => {
    const base = {
      prefill: {
        previous: previousSnapshot({ assets: [{ assetClassCode: "cash", value: "800.00" }] }),
        activeLiabilities: [],
      },
    };
    const withEstimate = computeCurrentWealth(
      input({ ...base, namedValues: { assets: {}, liabilities: {}, cash: "950.00" } }),
    );
    expect(withEstimate.cash.toFixed(2)).toBe("950.00");

    const withoutEstimate = computeCurrentWealth(input(base));
    expect(withoutEstimate.cash.toFixed(2)).toBe("800.00");
  });

  it("values named assets and liabilities computed-first, then previous snapshot, then latest entry", () => {
    const w = computeCurrentWealth(
      input({
        prefill: {
          previous: previousSnapshot({
            namedAssets: [{ assetId: "home", name: "home", value: "200000.00" }],
            liabilities: [{ liabilityId: "mortgage", liabilityName: "mortgage", balance: "150000.00" }],
          }),
          activeLiabilities: [],
        },
        namedAssets: [
          namedAsset("home", { latestValue: "999999.00" }),
          namedAsset("car", { latestValue: "12000.00" }),
        ],
        liabilities: [
          liability("mortgage", { latestBalance: "999999.00" }),
          liability("car-loan", { latestBalance: "8000.00" }),
        ],
        // Computed value wins for home/mortgage; car and car-loan fall back
        // (no snapshot entry) to their latest entry.
        namedValues: {
          assets: { home: "210000.00" },
          liabilities: { mortgage: "148000.00" },
          cash: null,
        },
      }),
    );
    expect(w.namedAssets.toFixed(2)).toBe("222000.00");
    expect(w.liabilities.toFixed(2)).toBe("156000.00");
  });

  it("ignores inactive named assets and liabilities", () => {
    const w = computeCurrentWealth(
      input({
        namedAssets: [namedAsset("old-car", { active: false, latestValue: "5000.00" })],
        liabilities: [liability("paid-loan", { active: false, latestBalance: "1000.00" })],
      }),
    );
    expect(w.namedAssets.isZero()).toBe(true);
    expect(w.liabilities.isZero()).toBe(true);
  });

  it("reports pension only when positive (hidden-when-zero contract)", () => {
    const withPension = computeCurrentWealth(
      input({
        prefill: {
          previous: previousSnapshot({ assets: [{ assetClassCode: "pension", value: "10000.00" }] }),
          activeLiabilities: [],
        },
      }),
    );
    expect(withPension.pension.gt(0)).toBe(true);

    const withoutPension = computeCurrentWealth(
      input({
        prefill: {
          previous: previousSnapshot({ assets: [{ assetClassCode: "cash", value: "100.00" }] }),
          activeLiabilities: [],
        },
      }),
    );
    expect(withoutPension.pension.isZero()).toBe(true);
  });

  it("is empty only when every component is zero", () => {
    expect(computeCurrentWealth(input()).isEmpty).toBe(true);
    expect(
      computeCurrentWealth(input({ liabilities: [liability("loan", { latestBalance: "10.00" })] })).isEmpty,
    ).toBe(false);
  });
});
