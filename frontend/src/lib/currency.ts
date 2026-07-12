// ISO 4217 currency helpers, sourced from the browser's built-in Intl tables.
// `Intl.supportedValuesOf("currency")` is available in all evergreen browsers (2022+).

const COMMON: readonly string[] = ["EUR", "USD", "GBP", "JPY", "CHF", "CAD", "AUD"];

function allCodes(): string[] {
  // `supportedValuesOf` returns sorted ISO codes. Cast guards against older TS lib targets.
  const intlWithSupported = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  return intlWithSupported.supportedValuesOf?.("currency") ?? [];
}

export interface CurrencyOption {
  code: string;
  label: string;
}

export function getCurrencyOptions(locale: string): CurrencyOption[] {
  const displayNames = new Intl.DisplayNames([locale], { type: "currency" });
  const codes = allCodes();
  if (codes.length === 0) return COMMON.map((c) => ({ code: c, label: c }));
  const common = COMMON.filter((c) => codes.includes(c)).map((c) => ({
    code: c,
    label: `${c} — ${displayNames.of(c) ?? c}`,
  }));
  const rest = codes
    .filter((c) => !COMMON.includes(c))
    .map((c) => ({ code: c, label: `${c} — ${displayNames.of(c) ?? c}` }));
  return [...common, ...rest];
}
