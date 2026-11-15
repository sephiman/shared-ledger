// EU/EEA + GB open-banking countries supported by Enable Banking (common ones first).
export const BANK_COUNTRIES = [
  "NL", "ES", "DE", "FR", "BE", "IT", "PT", "IE", "AT", "FI", "GB",
  "BG", "HR", "CY", "CZ", "DK", "EE", "GR", "HU", "IS", "LV", "LI",
  "LT", "LU", "MT", "NO", "PL", "RO", "SK", "SI", "SE",
] as const;

const SUPPORTED_COUNTRIES = new Set<string>(BANK_COUNTRIES);
const FALLBACK_COUNTRY = "ES";

const TIMEZONE_TO_COUNTRY: Record<string, string> = {
  "Europe/Vienna": "AT",
  "Europe/Brussels": "BE",
  "Europe/Sofia": "BG",
  "Europe/Zagreb": "HR",
  "Asia/Nicosia": "CY",
  "Asia/Famagusta": "CY",
  "Europe/Nicosia": "CY",
  "Europe/Prague": "CZ",
  "Europe/Copenhagen": "DK",
  "Europe/Tallinn": "EE",
  "Europe/Helsinki": "FI",
  "Europe/Mariehamn": "FI",
  "Europe/Paris": "FR",
  "America/Guadeloupe": "FR",
  "America/Martinique": "FR",
  "America/Cayenne": "FR",
  "Indian/Reunion": "FR",
  "Indian/Mayotte": "FR",
  "Europe/Berlin": "DE",
  "Europe/Busingen": "DE",
  "Europe/Athens": "GR",
  "Europe/Budapest": "HU",
  "Atlantic/Reykjavik": "IS",
  "Europe/Dublin": "IE",
  "Europe/Rome": "IT",
  "Europe/Riga": "LV",
  "Europe/Vaduz": "LI",
  "Europe/Vilnius": "LT",
  "Europe/Luxembourg": "LU",
  "Europe/Malta": "MT",
  "Europe/Amsterdam": "NL",
  "Europe/Oslo": "NO",
  "Arctic/Longyearbyen": "NO",
  "Europe/Warsaw": "PL",
  "Europe/Lisbon": "PT",
  "Atlantic/Madeira": "PT",
  "Atlantic/Azores": "PT",
  "Europe/Bucharest": "RO",
  "Europe/Bratislava": "SK",
  "Europe/Ljubljana": "SI",
  "Europe/Madrid": "ES",
  "Atlantic/Canary": "ES",
  "Africa/Ceuta": "ES",
  "Europe/Stockholm": "SE",
  "Europe/London": "GB",
  "Europe/Belfast": "GB",
  "Europe/Guernsey": "GB",
  "Europe/Jersey": "GB",
  "Europe/Isle_of_Man": "GB",
};

function currentTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone ?? "";
  } catch {
    return "";
  }
}

function currentLanguages(): readonly string[] {
  if (typeof navigator === "undefined") return [];
  if (Array.isArray(navigator.languages) && navigator.languages.length > 0) return navigator.languages;
  return navigator.language ? [navigator.language] : [];
}

function isGenericTimeZone(timeZone: string): boolean {
  const upper = timeZone.trim().toUpperCase();
  return upper === "" || upper === "UTC" || upper === "GMT" || upper.startsWith("ETC/");
}

function parseLocale(tag: string): Intl.Locale | null {
  try {
    return new Intl.Locale(tag);
  } catch {
    return null;
  }
}

function countryFromLanguages(languages: readonly string[], allowMaximize: boolean): string | null {
  const locales = languages.map(parseLocale).filter((l): l is Intl.Locale => l !== null);
  for (const locale of locales) {
    const region = locale.region?.toUpperCase();
    if (region && SUPPORTED_COUNTRIES.has(region)) return region;
  }
  if (!allowMaximize) return null;
  for (const locale of locales) {
    if (!locale.region) {
      const region = locale.maximize().region?.toUpperCase();
      if (region && SUPPORTED_COUNTRIES.has(region)) return region;
    }
  }
  return null;
}

/** Picks the initial bank country from the viewer's timezone, falling back to browser locales and NL. */
export function defaultBankCountry(
  timeZone: string = currentTimeZone(),
  languages: readonly string[] = currentLanguages(),
): string {
  const byZone = TIMEZONE_TO_COUNTRY[timeZone];
  if (byZone && SUPPORTED_COUNTRIES.has(byZone)) return byZone;
  return countryFromLanguages(languages, isGenericTimeZone(timeZone)) ?? FALLBACK_COUNTRY;
}
