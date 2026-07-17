import { describe, expect, it } from "vitest";
import { niceMoneyTicks } from "./fireTicks";

describe("niceMoneyTicks", () => {
  it("produces sorted ascending ticks from zero", () => {
    const ticks = niceMoneyTicks(950_000);
    expect(ticks[0]).toBe(0);
    const sorted = [...ticks].sort((a, b) => a - b);
    expect(ticks).toEqual(sorted);
    expect(ticks[ticks.length - 1]).toBeGreaterThanOrEqual(950_000);
  });

  it("uses round steps", () => {
    const ticks = niceMoneyTicks(950_000);
    const step = ticks[1] - ticks[0];
    expect([100_000, 200_000, 250_000, 500_000]).toContain(step);
    for (let i = 1; i < ticks.length; i++) expect(ticks[i] - ticks[i - 1]).toBeCloseTo(step);
  });

  it("keeps a reasonable tick count", () => {
    for (const max of [1, 42, 999, 12_345, 987_654, 3_141_592]) {
      const count = niceMoneyTicks(max).length;
      expect(count).toBeGreaterThanOrEqual(3);
      expect(count).toBeLessThanOrEqual(8);
    }
  });

  it("degrades gracefully on empty data", () => {
    expect(niceMoneyTicks(0)).toEqual([0]);
    expect(niceMoneyTicks(Number.NaN)).toEqual([0]);
  });
});
