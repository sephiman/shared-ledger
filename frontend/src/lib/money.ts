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

/**
 * Full-precision per-unit price. Shows every decimal the value actually carries —
 * PEPE 0.000002403511 keeps all 12 decimals, BTC 55,567.73 keeps 2 — with NO rounding
 * and NO significant-figure truncation; the decimal count comes from the value itself.
 * European grouping and currency symbol. Use for unit/current prices only; EUR
 * aggregates (totals, P&L) stay at 2 decimals via {@link formatMoney}.
 */
export function formatPrice(value: string | number | Decimal | null | undefined, currency: string, locale: string): string {
  const d = toDecimal(value);
  // Never fewer than 2 (so 5 reads as 5.00); cap at Intl's 20-fraction-digit ceiling.
  const decimals = Math.min(20, Math.max(2, d.decimalPlaces()));
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: decimals,
  }).format(d.toNumber());
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

/**
 * Compact money for narrow chart axes, with locale (European) grouping and the
 * currency symbol after the abbreviation: 70000 -> "70k€", 1_500_000 -> "1,5M€".
 * Full precision belongs in tooltips via {@link formatMoney}.
 */
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
