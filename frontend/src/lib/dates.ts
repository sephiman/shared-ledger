import { addDays, format, parseISO, type Locale } from "date-fns";
import { enUS, es as esLocale } from "date-fns/locale";

const locales: Record<string, Locale> = {
  en: enUS,
  es: esLocale,
};

export function formatDate(value: string | Date, locale: string, pattern = "PP"): string {
  const d = typeof value === "string" ? parseISO(value) : value;
  return format(d, pattern, { locale: locales[locale] ?? enUS });
}

/**
 * Day, named month, year — "31 Jul 2026" in English, "31 jul 2026" in Spanish. One fixed order in every
 * locale, unlike the default `PP` pattern, which reorders to "Jul 31, 2026" in English.
 *
 * Use wherever a date must be unambiguous without the reader knowing which locale convention is in play
 * — both ends of a range, above all. The named month is what makes day-first safe: "31/07" vs "07/31"
 * would not be. Native date inputs keep rendering whatever numeric format the browser picks; text
 * formatted here is the authoritative one.
 *
 * Anything that is not a full ISO date passes through untouched.
 */
export function formatDayMonthYear(value: string, locale: string): string {
  if (!/^\d{4}-\d{2}-\d{2}/.test(value)) return value;
  return formatDate(value, locale, "d MMM yyyy");
}

export function addDaysIso(iso: string, days: number): string {
  return format(addDays(parseISO(iso), days), "yyyy-MM-dd");
}

const pad2 = (n: number) => String(n).padStart(2, "0");

/** Today's date in the browser's LOCAL calendar (not UTC) as yyyy-MM-dd. */
export function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

export function monthBounds(year: number, month: number): { from: string; to: string } {
  const lastDay = new Date(year, month, 0).getDate();
  return { from: `${year}-${pad2(month)}-01`, to: `${year}-${pad2(month)}-${pad2(lastDay)}` };
}

export function yearBounds(year: number): { from: string; to: string } {
  return { from: `${year}-01-01`, to: `${year}-12-31` };
}

/** Month name on its own, from the same formatter as every other date in the app. Built on date-fns
 *  rather than `toLocaleString` so a month reads identically here and inside {@link formatDayMonthYear},
 *  and doesn't shift with the browser's ICU version (Intl abbreviates Spanish September "sept", date-fns
 *  "sep" — one source means one answer). */
export function monthName(month: number, locale: string, width: "long" | "short" = "long"): string {
  return formatDate(new Date(2000, month - 1, 1), locale, width === "short" ? "MMM" : "MMMM");
}

// ISO week day: 1=Mon ... 7=Sun
export function weekdayName(isoDay: number, locale: string, width: "long" | "short" = "long"): string {
  // JS Sunday=0..Saturday=6. ISO 1=Mon (jsDay=1) ... 7=Sun (jsDay=0).
  const jsDay = isoDay === 7 ? 0 : isoDay;
  // 2024-01-07 is a Sunday; use a known Sunday as base and add.
  const base = new Date(Date.UTC(2024, 0, 7));
  base.setUTCDate(base.getUTCDate() + jsDay);
  return base.toLocaleString(locale, { weekday: width, timeZone: "UTC" });
}
