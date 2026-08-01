import type { HoldingSummary } from "@/api/portfolio";
import { formatDate } from "@/lib/dates";

/** How old each holding's price is, and whether that is old enough to point out. The measure follows the
 *  asset class's refresh cadence: **crypto** refreshes intraday so its age comes from the observation
 *  instant, while **ETFs/stocks** get one price per trading day so their age counts calendar days (the
 *  instant would read "8h ago" for a Friday close seen Monday). No market calendar — the equity tolerance
 *  already covers a weekend plus a holiday. */

/** Crypto refreshes roughly hourly; beyond this a run has been missed. */
export const CRYPTO_STALE_AFTER_HOURS = 3;

/** Equities publish one close per trading day, and four calendar days spans a long weekend, so only a
 *  genuinely missed refresh trips it. */
export const EQUITY_STALE_AFTER_DAYS = 4;

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

export interface PriceAge {
  /** Age in milliseconds; the ordering key for "which price is the oldest". */
  ageMs: number;
  /** Past this class's tolerance — worth highlighting, unlike a normal weekend gap. */
  stale: boolean;
  /** The moment (ISO instant) or trading day (ISO date) the price refers to, for the tooltip. */
  asOf: string;
  /** True when only the trading day is meaningful: day-granular label, date-only tooltip. */
  dayGranular: boolean;
}

/** Age of one holding's price, or null when there is nothing to date: a closed position, an unpriced
 *  holding, or a fund — funds have no provider and their "price" is whatever a snapshot last recorded. */
export function priceAge(row: HoldingSummary, now: Date): PriceAge | null {
  if (row.holding.closed || row.holding.assetClass === "fund" || row.currentPrice == null) return null;

  if (row.holding.assetClass === "crypto") {
    if (!row.priceObservedAt) return null;
    const observed = new Date(row.priceObservedAt).getTime();
    if (Number.isNaN(observed)) return null;
    // Clamp: a clock skew between server and browser must not read as a future price.
    const ageMs = Math.max(0, now.getTime() - observed);
    return {
      ageMs,
      stale: ageMs > CRYPTO_STALE_AFTER_HOURS * HOUR_MS,
      asOf: row.priceObservedAt,
      dayGranular: false,
    };
  }

  if (!row.priceAsOf) return null;
  const days = calendarDaysAgo(row.priceAsOf, now);
  if (days == null) return null;
  return {
    ageMs: days * DAY_MS,
    stale: days > EQUITY_STALE_AFTER_DAYS,
    asOf: row.priceAsOf,
    dayGranular: true,
  };
}

/** The worst age among holdings actually past their tolerance — the figure behind the stale-price notice,
 *  null when nothing is stale. Rows without an age drop out on their own. */
export function oldestStalePriceAge(rows: HoldingSummary[], now: Date): PriceAge | null {
  let oldest: PriceAge | null = null;
  for (const row of rows) {
    const age = priceAge(row, now);
    if (age?.stale && (!oldest || age.ageMs > oldest.ageMs)) oldest = age;
  }
  return oldest;
}

/** The moment a price refers to: the trading day for a daily close, day plus clock time when the cadence is
 *  intraday. Shown in the expanded holding. */
export function priceAsOfLabel(age: PriceAge, locale: string): string {
  return formatDate(age.asOf, locale, age.dayGranular ? "PP" : "PPp");
}

/** Compact localized age: "2h ago" / "3d ago", "hace 2 h" / "hace 3 d". Day-granular ages never borrow a
 *  smaller unit — a daily close is not "8 hours" fresh. */
export function formatPriceAge(age: PriceAge, locale: string): string {
  const days = Math.floor(age.ageMs / DAY_MS);
  if (age.dayGranular || days >= 1) {
    // 0 days on a daily close: today's price. "today" beats "0d ago".
    if (days === 0) return relative(locale, "auto").format(0, "day");
    return relative(locale, "always").format(-days, "day");
  }
  const hours = Math.floor(age.ageMs / HOUR_MS);
  if (hours >= 1) return relative(locale, "always").format(-hours, "hour");
  const minutes = Math.floor(age.ageMs / (60 * 1000));
  return relative(locale, "always").format(-Math.max(1, minutes), "minute");
}

/** Calendar days between an ISO date and now, in the browser's own calendar. Null if unparseable. */
function calendarDaysAgo(isoDate: string, now: Date): number | null {
  const [year, month, day] = isoDate.split("-").map(Number);
  if (!year || !month || !day) return null;
  const priceDay = Date.UTC(year, month - 1, day);
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.max(0, Math.round((today - priceDay) / DAY_MS));
}

function relative(locale: string, numeric: "auto" | "always"): Intl.RelativeTimeFormat {
  return new Intl.RelativeTimeFormat(locale, { numeric, style: "narrow" });
}
