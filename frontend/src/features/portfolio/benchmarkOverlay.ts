import type { BenchmarkSeries } from "@/api/portfolio";

// Distinct hues for benchmark overlays, assigned in the registry's order so a new benchmark
// (a new DB row) gets a stable colour with no chart code change. Kept clear of the ROI series.
export const BENCHMARK_PALETTE = ["#2563eb", "#db2777", "#0d9488", "#7c3aed", "#ca8a04", "#dc2626"];

/** Colour per benchmark key, by registry order. Unknown keys fall off the end and rotate. */
export function benchmarkColors(keys: string[]): Record<string, string> {
  const map: Record<string, string> = {};
  keys.forEach((k, i) => {
    map[k] = BENCHMARK_PALETTE[i % BENCHMARK_PALETTE.length];
  });
  return map;
}

/**
 * Benchmark TWR points (fractions over the wire) for the selected benchmarks, keyed by date
 * and expressed in percentage points to match the ROI axis. A missing point (data gap) stays
 * null so the chart line breaks there rather than faking a value. Benchmark series share the
 * ROI curve's sample dates, so these merge onto the ROI rows by date.
 */
export function benchmarkColumnsByDate(
  series: BenchmarkSeries[] | undefined,
  selected: string[],
): Map<string, Record<string, number | null>> {
  const byDate = new Map<string, Record<string, number | null>>();
  for (const s of series ?? []) {
    if (!selected.includes(s.key)) continue;
    for (const p of s.points) {
      const entry = byDate.get(p.date) ?? {};
      entry[`bench_${s.key}`] = p.twrPct == null ? null : Number(p.twrPct) * 100;
      byDate.set(p.date, entry);
    }
  }
  return byDate;
}
