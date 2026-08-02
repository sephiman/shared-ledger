import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import {
  addMonths,
  formatMonthSpan,
  isRangeComplete,
  monthsInclusive,
  rangeLabel,
  resolveDayBounds,
  resolveMonthRange,
  resolveRange,
  snappedMonthSpan,
  type RangeValue,
} from "./range";

// Mid-month so day-precision and month-precision resolution are visibly different.
const NOW = new Date(2026, 6, 17, 12, 0, 0); // 2026-07-17, local time

const range = (over: Partial<RangeValue>): RangeValue => ({ preset: "1y", from: "", to: "", ...over });

beforeAll(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterAll(() => {
  vi.useRealTimers();
});

describe("resolveRange (day precision)", () => {
  it("counts a fixed preset back from today and leaves the upper bound open", () => {
    expect(resolveRange(range({ preset: "3m" }))).toEqual({ from: "2026-04-17", to: undefined });
    expect(resolveRange(range({ preset: "2y" }))).toEqual({ from: "2024-07-17", to: undefined });
  });

  it("starts year to date on 1 January of the current year", () => {
    expect(resolveRange(range({ preset: "ytd" }))).toEqual({ from: "2026-01-01", to: undefined });
  });

  it("leaves both bounds open for all time", () => {
    expect(resolveRange(range({ preset: "all" }))).toEqual({});
  });

  it("passes a custom range through untouched", () => {
    expect(resolveRange(range({ preset: "custom", from: "2025-03-14", to: "2026-02-09" }))).toEqual({
      from: "2025-03-14",
      to: "2026-02-09",
    });
  });
});

describe("resolveDayBounds", () => {
  it("fills the open upper bound with today", () => {
    expect(resolveDayBounds(range({ preset: "6m" }))).toEqual({ from: "2026-01-17", to: "2026-07-17" });
  });

  it("uses the supplied floor for all time", () => {
    expect(resolveDayBounds(range({ preset: "all" }), "2019-01-01")).toEqual({
      from: "2019-01-01",
      to: "2026-07-17",
    });
  });

  it("falls back to the start of the current year when no floor is known", () => {
    expect(resolveDayBounds(range({ preset: "all" }))).toEqual({ from: "2026-01-01", to: "2026-07-17" });
  });
});

describe("resolveMonthRange (whole months)", () => {
  it("returns N trailing whole months including the current one", () => {
    expect(resolveMonthRange(range({ preset: "3m" }))).toEqual({ from: "2026-05-01", to: "2026-07-31" });
    expect(resolveMonthRange(range({ preset: "1y" }))).toEqual({ from: "2025-08-01", to: "2026-07-31" });
    expect(resolveMonthRange(range({ preset: "2y" }))).toEqual({ from: "2024-08-01", to: "2026-07-31" });
  });

  it("runs year to date from January through the end of the current month", () => {
    expect(resolveMonthRange(range({ preset: "ytd" }))).toEqual({ from: "2026-01-01", to: "2026-07-31" });
  });

  it("leaves all time for the server to resolve against real data", () => {
    expect(resolveMonthRange(range({ preset: "all" }))).toEqual({});
  });

  it("snaps a custom range outwards to whole months", () => {
    expect(resolveMonthRange(range({ preset: "custom", from: "2025-03-14", to: "2026-02-09" }))).toEqual({
      from: "2025-03-01",
      to: "2026-02-28",
    });
  });

  it("snaps the upper bound to a leap-year February", () => {
    expect(resolveMonthRange(range({ preset: "custom", from: "2024-02-05", to: "2024-02-20" }))).toEqual({
      from: "2024-02-01",
      to: "2024-02-29",
    });
  });

  it("leaves a half-filled custom range partially unbounded rather than guessing", () => {
    expect(resolveMonthRange(range({ preset: "custom", from: "2025-03-14", to: "" }))).toEqual({
      from: "2025-03-01",
      to: undefined,
    });
  });
});

describe("snappedMonthSpan", () => {
  it("reports the whole-month span and its length for a custom range", () => {
    expect(snappedMonthSpan(range({ preset: "custom", from: "2025-03-14", to: "2026-02-09" }))).toEqual({
      from: { year: 2025, month: 3 },
      to: { year: 2026, month: 2 },
      months: 12,
    });
  });

  it("counts a within-one-month range as one month", () => {
    expect(snappedMonthSpan(range({ preset: "custom", from: "2025-03-02", to: "2025-03-28" }))?.months).toBe(1);
  });

  it("returns nothing for presets, incomplete dates, or a reversed range", () => {
    expect(snappedMonthSpan(range({ preset: "1y" }))).toBeNull();
    expect(snappedMonthSpan(range({ preset: "custom", from: "2025-03-14", to: "" }))).toBeNull();
    expect(snappedMonthSpan(range({ preset: "custom", from: "2026-02-09", to: "2025-03-14" }))).toBeNull();
  });
});

describe("month arithmetic", () => {
  it("rolls over year boundaries in both directions", () => {
    expect(addMonths({ year: 2026, month: 1 }, -1)).toEqual({ year: 2025, month: 12 });
    expect(addMonths({ year: 2026, month: 12 }, 1)).toEqual({ year: 2027, month: 1 });
    expect(addMonths({ year: 2026, month: 7 }, -18)).toEqual({ year: 2025, month: 1 });
  });

  it("counts both ends of an inclusive span", () => {
    expect(monthsInclusive({ year: 2025, month: 3 }, { year: 2026, month: 2 })).toBe(12);
    expect(monthsInclusive({ year: 2026, month: 7 }, { year: 2026, month: 7 })).toBe(1);
  });
});

describe("formatMonthSpan", () => {
  it("renders both ends of a multi-month span", () => {
    expect(formatMonthSpan({ year: 2025, month: 3 }, { year: 2026, month: 2 }, "en")).toBe("Mar 2025 – Feb 2026");
  });

  it("collapses a single-month span", () => {
    expect(formatMonthSpan({ year: 2026, month: 7 }, { year: 2026, month: 7 }, "en")).toBe("Jul 2026");
  });

  it("keeps named month before year in Spanish too", () => {
    expect(formatMonthSpan({ year: 2025, month: 3 }, { year: 2026, month: 2 }, "es")).toBe("mar 2025 – feb 2026");
  });
});

describe("isRangeComplete", () => {
  it("accepts every preset", () => {
    for (const preset of ["3m", "6m", "ytd", "1y", "2y", "all"] as const) {
      expect(isRangeComplete(range({ preset }))).toBe(true);
    }
  });

  it("requires both ends of a custom range, correctly ordered", () => {
    expect(isRangeComplete(range({ preset: "custom", from: "2025-03-01", to: "2026-02-28" }))).toBe(true);
    expect(isRangeComplete(range({ preset: "custom", from: "2025-03-01", to: "" }))).toBe(false);
    expect(isRangeComplete(range({ preset: "custom", from: "", to: "2026-02-28" }))).toBe(false);
    expect(isRangeComplete(range({ preset: "custom", from: "2026-02-28", to: "2025-03-01" }))).toBe(false);
  });
});

describe("rangeLabel", () => {
  const t = (key: string, options?: Record<string, unknown>) =>
    options?.count === undefined ? key : `${key}:${String(options.count)}`;

  it("labels presets through i18n, pluralising years by count", () => {
    expect(rangeLabel(range({ preset: "3m" }), t, "en")).toBe("range.months:3");
    expect(rangeLabel(range({ preset: "1y" }), t, "en")).toBe("range.year:1");
    expect(rangeLabel(range({ preset: "2y" }), t, "en")).toBe("range.year:2");
    expect(rangeLabel(range({ preset: "ytd" }), t, "en")).toBe("range.ytd");
    expect(rangeLabel(range({ preset: "all" }), t, "en")).toBe("range.all");
  });

  it("spells out a complete custom range in day–month-name–year order, in both languages", () => {
    const v = range({ preset: "custom", from: "2026-07-31", to: "2026-08-02" });
    expect(rangeLabel(v, t, "en")).toBe("31 Jul 2026 – 2 Aug 2026");
    expect(rangeLabel(v, t, "es")).toBe("31 jul 2026 – 2 ago 2026");
  });

  it("falls back while one end is missing", () => {
    expect(rangeLabel(range({ preset: "custom", from: "", to: "" }), t, "en")).toBe("range.custom");
  });
});
