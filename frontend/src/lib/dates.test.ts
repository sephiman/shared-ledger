import { describe, expect, it } from "vitest";
import { formatDayMonthYear, monthName } from "./dates";

describe("formatDayMonthYear", () => {
  // One order in every language: day, named month, year — never the locale-native reordering.
  it("renders day, named month, year in English", () => {
    expect(formatDayMonthYear("2026-07-31", "en")).toBe("31 Jul 2026");
    expect(formatDayMonthYear("2026-08-02", "en")).toBe("2 Aug 2026");
  });

  it("renders the same order in Spanish", () => {
    expect(formatDayMonthYear("2026-07-31", "es")).toBe("31 jul 2026");
    expect(formatDayMonthYear("2026-08-02", "es")).toBe("2 ago 2026");
  });

  it("drops the leading zero from single-digit days", () => {
    expect(formatDayMonthYear("2026-01-09", "en")).toBe("9 Jan 2026");
    expect(formatDayMonthYear("2026-01-09", "es")).toBe("9 ene 2026");
  });

  it("never emits a numeric or reordered date in any supported locale", () => {
    for (const locale of ["en", "es"]) {
      const out = formatDayMonthYear("2026-07-31", locale);
      expect(out).toMatch(/^31 \D+ 2026$/);
      expect(out).not.toContain("/");
      expect(out).not.toContain(",");
    }
  });

  it("falls back to English for an unknown locale rather than throwing", () => {
    expect(formatDayMonthYear("2026-07-31", "de")).toBe("31 Jul 2026");
  });

  it("passes through anything that is not a full ISO date", () => {
    expect(formatDayMonthYear("", "en")).toBe("");
    expect(formatDayMonthYear("2026-07", "en")).toBe("2026-07");
    expect(formatDayMonthYear("not-a-date", "en")).toBe("not-a-date");
  });

  it("names a month exactly the way the standalone month helper does", () => {
    // The two must not drift: a heatmap column header and a range label sit side by side.
    for (const locale of ["en", "es"]) {
      for (let m = 1; m <= 12; m++) {
        const iso = `2026-${String(m).padStart(2, "0")}-15`;
        expect(formatDayMonthYear(iso, locale)).toBe(`15 ${monthName(m, locale, "short")} 2026`);
      }
    }
  });
});

describe("monthName", () => {
  it("abbreviates and spells out months per locale", () => {
    expect(monthName(9, "en", "short")).toBe("Sep");
    expect(monthName(9, "en", "long")).toBe("September");
    expect(monthName(9, "es", "short")).toBe("sep");
    expect(monthName(9, "es", "long")).toBe("septiembre");
  });
});
