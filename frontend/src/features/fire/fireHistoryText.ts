import type { TFunction } from "i18next";
import type { FireAttribution, FireHistory, FireHistoryPoint } from "@/api/fire";
import { formatNumber } from "@/lib/money";
import { formatDayMonthYear } from "@/lib/dates";
import { estimatedYearText, formatFirePercent } from "./fireLabels";

/** A point within this many days of exactly a year back reads as "a year ago"; otherwise its date is named. */
const YEAR_AGO_TOLERANCE_DAYS = 46;
const DAYS_PER_YEAR = 365;
const MS_PER_DAY = 86_400_000;

function daysBetween(fromIso: string, toIso: string): number {
  return Math.round((Date.parse(`${toIso}T00:00:00Z`) - Date.parse(`${fromIso}T00:00:00Z`)) / MS_PER_DAY);
}

/** "A year ago" when the point is about a year before `toIso`, "On 3 Dec 2025" otherwise. */
export function thenLabel(fromIso: string, toIso: string, locale: string, t: TFunction): string {
  return Math.abs(daysBetween(fromIso, toIso) - DAYS_PER_YEAR) <= YEAR_AGO_TOLERANCE_DAYS
    ? t("fire.history_then_year_ago")
    : t("fire.history_then_on", { date: formatDayMonthYear(fromIso, locale) });
}

function coverageText(point: FireHistoryPoint, locale: string): string {
  return formatFirePercent(point.coveragePercent, locale, 0);
}

/** "A year ago: 14 % · 2049. Today: 17 % · 2046." — compared against the attribution's starting point. */
export function historySummary(history: FireHistory, locale: string, t: TFunction): string | null {
  const now = history.points.at(-1);
  if (!now) return null;
  const nowYear = estimatedYearText(now.estimatedYear, history.horizonYear, t);
  const from = history.attribution
    ? history.points.find((p) => p.date === history.attribution?.fromDate)
    : undefined;
  if (!from) {
    return t("fire.history_summary_only_today", { coverage: coverageText(now, locale), year: nowYear });
  }
  return t("fire.history_summary", {
    then: thenLabel(from.date, now.date, locale, t),
    thenCoverage: coverageText(from, locale),
    thenYear: estimatedYearText(from.estimatedYear, history.horizonYear, t),
    nowCoverage: coverageText(now, locale),
    nowYear,
  });
}

/** Signed percentage points with one decimal: "+2.1", "−0.5" (typographic minus). */
export function signedPoints(value: string | number, locale: string): string {
  const n = Number(value);
  const abs = formatNumber(Math.abs(n), locale, 1);
  if (n > 0) return `+${abs}`;
  if (n < 0) return `−${abs}`;
  return abs;
}

/** "You gained 3.0 points: +2.1 from what you put in, +1.4 from the market, −0.5 because you need more." */
export function attributionSentence(a: FireAttribution, locale: string, t: TFunction): string {
  const total = Number(a.totalPoints);
  const need = Number(a.needPoints);
  const needKey =
    need < 0 ? "fire.attribution_part_need_more" : need > 0 ? "fire.attribution_part_need_less" : "fire.attribution_part_need_same";
  const needPart = t(needKey, { points: signedPoints(need, locale) });
  const wealthParts =
    a.contributedPoints !== null && a.marketPoints !== null
      ? [
          t("fire.attribution_part_contributed", { points: signedPoints(a.contributedPoints, locale) }),
          t("fire.attribution_part_market", { points: signedPoints(a.marketPoints, locale) }),
        ]
      : [t("fire.attribution_part_wealth", { points: signedPoints(a.wealthPoints, locale) })];
  const lead = t(total < 0 ? "fire.attribution_lost" : "fire.attribution_gained", {
    points: formatNumber(Math.abs(total), locale, 1),
  });
  return `${lead}: ${[...wealthParts, needPart].join(", ")}.`;
}
