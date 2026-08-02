import { describe, expect, it } from "vitest";
import { defaultRange } from "@/lib/range";
import { buildEvolutionRows, filterSnapshotsByRange, LIABILITIES_KEY, type SnapshotLike } from "./evolution";

const assetClasses = [{ code: "cash" }, { code: "stocks" }, { code: "crypto" }];

const snapshots: SnapshotLike[] = [
  {
    snapshotDate: "2023-01-31",
    totalLiabilities: "100",
    assets: [
      { assetClassCode: "cash", value: "1000" },
      { assetClassCode: "stocks", value: "500" },
      { assetClassCode: "crypto", value: "200" },
    ],
  },
  {
    snapshotDate: "2024-06-30",
    totalLiabilities: "50",
    assets: [
      { assetClassCode: "cash", value: "1200" },
      { assetClassCode: "stocks", value: "800" },
      { assetClassCode: "crypto", value: "0" },
    ],
  },
];

describe("filterSnapshotsByRange", () => {
  it("returns all snapshots for the 'all' preset", () => {
    expect(filterSnapshotsByRange(snapshots, defaultRange("all"))).toHaveLength(2);
  });

  it("keeps only snapshots within a custom from/to window", () => {
    const range = { preset: "custom" as const, from: "2024-01-01", to: "2024-12-31" };
    expect(filterSnapshotsByRange(snapshots, range).map((s) => s.snapshotDate)).toEqual(["2024-06-30"]);
  });

  it("returns an empty list (no error) when nothing falls in the window", () => {
    const range = { preset: "custom" as const, from: "2019-01-01", to: "2019-12-31" };
    expect(filterSnapshotsByRange(snapshots, range)).toEqual([]);
  });
});

describe("buildEvolutionRows", () => {
  const noContributions = new Map<string, number>();

  it("nets all assets minus liabilities when nothing is hidden", () => {
    const [row] = buildEvolutionRows(snapshots, assetClasses, noContributions, new Set());
    expect(row.netWorth).toBe(1000 + 500 + 200 - 100); // 1600
  });

  it("recomputes net worth from the visible classes when one is hidden", () => {
    const [row] = buildEvolutionRows(snapshots, assetClasses, noContributions, new Set(["crypto"]));
    expect(row.netWorth).toBe(1000 + 500 - 100); // 1400 — crypto band dropped
  });

  it("drops liabilities from net worth when liabilities are hidden", () => {
    const [row] = buildEvolutionRows(snapshots, assetClasses, noContributions, new Set([LIABILITIES_KEY]));
    expect(row.netWorth).toBe(1000 + 500 + 200); // 1700 — no liabilities subtracted
  });

  it("still records hidden class values on the row for the tooltip", () => {
    const [row] = buildEvolutionRows(snapshots, assetClasses, noContributions, new Set(["crypto"]));
    expect(row.crypto).toBe(200);
  });

  it("carries contributions through by snapshot date", () => {
    const contrib = new Map([["2024-06-30", 900]]);
    const rows = buildEvolutionRows(snapshots, assetClasses, contrib, new Set());
    expect(rows[0].contributions).toBeNull();
    expect(rows[1].contributions).toBe(900);
  });
});
