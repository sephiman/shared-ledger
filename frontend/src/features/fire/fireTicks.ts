/** "Nice" ascending ticks for a zero-based money axis: steps of 1 / 2 / 2.5 / 5 × 10^k. Recharts' automatic
 *  ticks occasionally render out of order with multi-series tuple areas; an explicit sorted list is deterministic. */
export function niceMoneyTicks(max: number, targetCount = 6): number[] {
  if (!Number.isFinite(max) || max <= 0) return [0];
  const rawStep = max / Math.max(1, targetCount - 1);
  const pow = Math.pow(10, Math.floor(Math.log10(rawStep)));
  const candidates = [1, 2, 2.5, 5, 10].map((m) => m * pow);
  const step = candidates.find((c) => c >= rawStep) ?? candidates[candidates.length - 1];
  // The last tick always reaches max, so [0, lastTick] is a valid domain for the data.
  const ticks: number[] = [0];
  let v = 0;
  while (v < max) {
    v += step;
    ticks.push(Math.round(v * 100) / 100);
  }
  return ticks;
}
