import Decimal from "decimal.js";
import type { TFunction } from "i18next";
import type { HoldingSummary, RefreshCadence } from "@/api/portfolio";
import { formatDayMonthYear } from "@/lib/dates";

export function isAlertEligibleHolding(row: HoldingSummary): boolean {
  return !row.holding.closed && row.holding.linked && row.holding.assetClass !== "fund";
}

export type InferredDirection = "ABOVE" | "BELOW" | "EQUAL" | "INVALID";

export function inferAlertDirection(
  targetRaw: string,
  currentPriceRaw: string | null | undefined,
): InferredDirection | null {
  const trimmed = targetRaw.trim();
  if (!trimmed || currentPriceRaw == null) return null;
  try {
    const target = new Decimal(trimmed);
    const current = new Decimal(currentPriceRaw);
    if (!target.isFinite() || target.lte(0)) return "INVALID";
    const cmp = target.cmp(current);
    if (cmp > 0) return "ABOVE";
    if (cmp < 0) return "BELOW";
    return "EQUAL";
  } catch {
    return "INVALID";
  }
}

export function applyPctShortcut(currentPriceRaw: string | null | undefined, pct: number): string | null {
  if (currentPriceRaw == null || !Number.isFinite(pct)) return null;
  try {
    const current = new Decimal(currentPriceRaw);
    if (!current.isFinite() || current.lte(0)) return null;
    const raw = current.times(new Decimal(1).plus(new Decimal(pct).div(100)));
    if (raw.lte(0)) return null;
    const defaultDp = current.abs().gte(1) ? 2 : Math.min(12, Math.max(4, current.decimalPlaces() + 2));
    let rounded = raw.toDecimalPlaces(defaultDp);
    if (rounded.eq(current) && pct !== 0) {
      rounded = raw.toDecimalPlaces(Math.min(12, Math.max(4, current.decimalPlaces() + 2)));
    }
    return rounded.toFixed();
  } catch {
    return null;
  }
}

export function describeCadence(cadence: RefreshCadence | null | undefined, t: TFunction): string {
  if (!cadence) return t("portfolio.alerts.cadence_fallback");
  switch (cadence.unit) {
    case "MINUTES":
      return t("portfolio.alerts.cadence_minutes", { count: cadence.interval });
    case "HOURS":
      return t(
        cadence.weekdaysOnly ? "portfolio.alerts.cadence_weekday_hours" : "portfolio.alerts.cadence_hours",
        { count: cadence.interval },
      );
    case "DAYS":
      if (cadence.interval === 1) {
        return cadence.dailyTime
          ? t("portfolio.alerts.cadence_daily", { time: cadence.dailyTime })
          : t("portfolio.alerts.cadence_daily_no_time");
      }
      return t("portfolio.alerts.cadence_days", { count: cadence.interval });
  }
}

/** Extracts YYYY-MM-DD in the given IANA zone so date-only comparisons and editors match server end-of-day. */
export function isoDateInZone(isoInstant: string, timeZone?: string): string {
  const date = new Date(isoInstant);
  if (Number.isNaN(date.getTime())) return "";
  try {
    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: timeZone || undefined,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(date);
    const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
    return `${get("year")}-${get("month")}-${get("day")}`;
  } catch {
    return isoInstant.slice(0, 10);
  }
}

/** Renders an instant using the app's d MMM yyyy format plus HH:mm in the display timezone. */
export function formatAlertTimestamp(isoInstant: string, locale: string, timeZone?: string): string {
  const date = new Date(isoInstant);
  if (Number.isNaN(date.getTime())) return isoInstant;
  const isoDay = isoDateInZone(isoInstant, timeZone);
  const dayLabel = formatDayMonthYear(isoDay, locale);
  try {
    const timePart = new Intl.DateTimeFormat(locale, {
      timeZone: timeZone || undefined,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(date);
    return `${dayLabel}, ${timePart}`;
  } catch {
    return dayLabel;
  }
}
