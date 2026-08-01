import type { BenchmarkSeries } from "@/api/portfolio";

// Each known benchmark reads with an intuitive hue (Bitcoin orange, Gold gold, …). Kept clear
// of the ROI series colour so the overlays stay legible against the user's own curve.
const BENCHMARK_COLORS: Record<string, string> = {
  gold: "#d4af37",
  bitcoin: "#f7931a",
  sp500: "#dc2626",
  msci_world: "#2563eb",
};

// Fallback hues for any future benchmark not in the map above, assigned in registry order so a
// new benchmark (a new DB row) still gets a stable colour with no chart code change.
export const BENCHMARK_PALETTE = ["#0d9488", "#7c3aed", "#db2777", "#ca8a04"];

/** Colour per benchmark key: known keys get their branded hue, unknown keys rotate the palette. */
export function benchmarkColors(keys: string[]): Record<string, string> {
  const map: Record<string, string> = {};
  let fallbackIdx = 0;
  keys.forEach((k) => {
    map[k] = BENCHMARK_COLORS[k] ?? BENCHMARK_PALETTE[fallbackIdx++ % BENCHMARK_PALETTE.length];
  });
  return map;
}

/** Benchmark TWR points keyed by date, in percentage points to match the ROI axis. A data gap stays null so
 *  the line breaks rather than faking a value. Benchmarks share the ROI curve's sample dates, so these
 *  merge onto the ROI rows by date. */
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
