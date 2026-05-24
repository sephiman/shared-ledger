import { format, parseISO, type Locale } from "date-fns";
import { enUS, es as esLocale } from "date-fns/locale";

const locales: Record<string, Locale> = {
  en: enUS,
  es: esLocale,
};

export function formatDate(value: string | Date, locale: string, pattern = "PP"): string {
  const d = typeof value === "string" ? parseISO(value) : value;
  return format(d, pattern, { locale: locales[locale] ?? enUS });
}

export function isoToday(): string {
  const d = new Date();
  return d.toISOString().slice(0, 10);
}

export function monthName(month: number, locale: string, width: "long" | "short" = "long"): string {
  return new Date(2000, month - 1, 1).toLocaleString(locale, { month: width });
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
