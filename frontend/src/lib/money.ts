import Decimal from "decimal.js";

Decimal.set({ precision: 20, rounding: Decimal.ROUND_HALF_EVEN });

export function toDecimal(value: string | number | Decimal | null | undefined): Decimal {
  if (value === null || value === undefined || value === "") return new Decimal(0);
  return new Decimal(value);
}

export function formatMoney(value: string | number | Decimal | null | undefined, currency: string, locale: string): string {
  const d = toDecimal(value);
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(d.toNumber());
}

/** Whole-unit money with a thousands separator even on four digits (Spanish Intl skips it: "1400 €" → "1.400 €"). */
export function formatWholeMoney(value: string | number | Decimal | null | undefined, currency: string, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency,
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
    useGrouping: "always",
  }).format(toDecimal(value).toNumber());
}

export const MAX_QUANTITY_DECIMALS = 6;

/** Fraction digits {@link formatPrice} uses on a standalone price: 2 at or above 1 unit, expanding to
 *  stored sub-unit decimals up to Intl's 20-digit limit so micro-priced coins never collapse to 0.00. */
export function priceDecimals(value: string | number | Decimal | null | undefined): number {
  const d = toDecimal(value);
  return d.abs().gte(1) ? 2 : Math.min(20, Math.max(2, d.decimalPlaces()));
}

function effectivePriceDecimals(
  d: Decimal,
  referencePrice?: string | number | Decimal | null,
): number {
  const own = priceDecimals(d);
  if (referencePrice == null || referencePrice === "") return own;
  return Math.min(own, priceDecimals(referencePrice));
}

/** Per-unit price or unit cost. 1 unit and above show 2 decimals; sub-unit values keep stored precision
 *  (never truncated when no reference cap applies), and when [referencePrice] is passed (unit cost
 *  paired with a market price), precision never exceeds the price shown alongside it. */
export function formatPrice(
  value: string | number | Decimal | null | undefined,
  currency: string,
  locale: string,
  referencePrice?: string | number | Decimal | null,
): string {
  const d = toDecimal(value);
  const decimals = effectivePriceDecimals(d, referencePrice);
  const rounded = d.toDecimalPlaces(decimals);
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: decimals,
  }).format(rounded.toNumber());
}

/** Unrounded currency string at full stored precision (min 2dp) for hover/assistive-tech tooltips. */
export function formatFullPrecisionMoney(
  value: string | number | Decimal | null | undefined,
  currency: string,
  locale: string,
): string {
  const d = toDecimal(value);
  const decimals = Math.min(20, Math.max(2, d.decimalPlaces()));
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: decimals,
  }).format(d.toNumber());
}

/** Plain decimal string rounded to the display precision of {@link formatPrice} for numeric input fields. */
export function formatPriceInput(
  value: string | number | Decimal | null | undefined,
  referencePrice?: string | number | Decimal | null,
): string {
  const d = toDecimal(value);
  const decimals = effectivePriceDecimals(d, referencePrice);
  const str = d.toDecimalPlaces(decimals).toFixed(decimals);
  // Keep at least 2dp (e.g. "2.10") while dropping extraneous trailing zeros ("0.790000" -> "0.79").
  return str.replace(/(\.\d{2,}?)0+$/, "$1");
}

/** Locale-formatted quantity with forced thousands grouping (matching {@link formatWholeMoney} for
 *  Spanish 4-digit numbers), trailing zeros stripped, and a capped decimal scale. */
export function formatQuantity(
  value: string | number | Decimal | null | undefined,
  locale: string,
  maxDecimals = MAX_QUANTITY_DECIMALS,
): string {
  const d = toDecimal(value);
  const cap = Math.max(0, Math.min(20, maxDecimals));
  const rounded = d.toDecimalPlaces(cap);
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: 0,
    maximumFractionDigits: Math.min(cap, rounded.decimalPlaces()),
    useGrouping: "always",
  }).format(rounded.toNumber());
}

/** Full-precision locale-formatted quantity for hover/assistive-tech tooltips. */
export function formatQuantityFull(
  value: string | number | Decimal | null | undefined,
  locale: string,
): string {
  const d = toDecimal(value);
  return formatQuantity(d, locale, Math.min(20, d.decimalPlaces()));
}

export function formatNumber(value: number, locale: string, fractionDigits = 0): string {
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(value);
}

function currencySymbol(currency: string, locale: string): string {
  const parts = new Intl.NumberFormat(locale, { style: "currency", currency, maximumFractionDigits: 0 }).formatToParts(0);
  return parts.find((p) => p.type === "currency")?.value ?? currency;
}

/** Compact money for narrow chart axes, currency symbol after the abbreviation: 70000 -> "70k€",
 *  1_500_000 -> "1,5M€". Full precision belongs in tooltips via {@link formatMoney}. */
export function formatCompactMoney(value: number, currency: string, locale: string): string {
  const sym = currencySymbol(currency, locale);
  const abs = Math.abs(value);
  if (abs >= 1_000_000) return `${formatNumber(value / 1_000_000, locale, 1)}M${sym}`;
  if (abs >= 1_000) return `${formatNumber(value / 1_000, locale, 0)}k${sym}`;
  return `${formatNumber(value, locale, 0)}${sym}`;
}

/** Formats an already-scaled percentage number (12.34 -> "12.3 %"), locale-aware. */
export function formatPercent(value: number, locale: string, fractionDigits = 1): string {
  return `${formatNumber(value, locale, fractionDigits)} %`;
}
