import { describe, expect, it } from "vitest";
import type { BenchmarkSeries } from "@/api/portfolio";
import { BENCHMARK_PALETTE, benchmarkColors, benchmarkColumnsByDate } from "./benchmarkOverlay";

function series(key: string, points: Array<[string, string | null]>): BenchmarkSeries {
  return {
    key,
    currency: "USD",
    points: points.map(([date, twrPct]) => ({ date, twrPct })),
    availableFrom: points[0]?.[0] ?? null,
    availableTo: points[points.length - 1]?.[0] ?? null,
    partial: points.some(([, v]) => v == null),
  };
}

describe("benchmarkColors", () => {
  it("assigns palette colours by registry order and rotates when exhausted", () => {
    const keys = [...Array(BENCHMARK_PALETTE.length + 1)].map((_, i) => `k${i}`);
    const colors = benchmarkColors(keys);
    expect(colors.k0).toBe(BENCHMARK_PALETTE[0]);
    expect(colors.k1).toBe(BENCHMARK_PALETTE[1]);
    // Wraps around past the end of the palette.
    expect(colors[`k${BENCHMARK_PALETTE.length}`]).toBe(BENCHMARK_PALETTE[0]);
  });
});

describe("benchmarkColumnsByDate", () => {
  it("converts fractions to percentage points for selected benchmarks only", () => {
    const s = [
      series("sp500", [["2026-01-01", "0"], ["2026-01-02", "0.1234"]]),
      series("gold", [["2026-01-01", "0"], ["2026-01-02", "0.05"]]),
    ];
    const byDate = benchmarkColumnsByDate(s, ["sp500"]);
    expect(byDate.get("2026-01-01")).toEqual({ bench_sp500: 0 });
    expect(byDate.get("2026-01-02")).toEqual({ bench_sp500: 12.34 });
    // gold was not selected, so no column for it.
    expect(byDate.get("2026-01-02")).not.toHaveProperty("bench_gold");
  });

  it("preserves a null (data gap) so the line breaks instead of faking a value", () => {
    const s = [series("gold", [["2026-01-01", null], ["2026-01-02", "0.1"]])];
    const byDate = benchmarkColumnsByDate(s, ["gold"]);
    expect(byDate.get("2026-01-01")).toEqual({ bench_gold: null });
    expect(byDate.get("2026-01-02")).toEqual({ bench_gold: 10 });
  });

  it("returns an empty map when nothing is selected", () => {
    const s = [series("sp500", [["2026-01-01", "0"]])];
    expect(benchmarkColumnsByDate(s, []).size).toBe(0);
    expect(benchmarkColumnsByDate(undefined, ["sp500"]).size).toBe(0);
  });
});
