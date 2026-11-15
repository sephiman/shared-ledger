import { describe, expect, it } from "vitest";
import { BANK_COUNTRIES, defaultBankCountry } from "./bankCountry";

describe("defaultBankCountry", () => {
  it("resolves European timezones to their bank country regardless of browser language", () => {
    expect(defaultBankCountry("Europe/Madrid", ["en-US"])).toBe("ES");
    expect(defaultBankCountry("Atlantic/Canary", ["en-US"])).toBe("ES");
    expect(defaultBankCountry("Europe/Amsterdam", ["en-US"])).toBe("NL");
    expect(defaultBankCountry("Europe/Berlin", ["en-GB"])).toBe("DE");
    expect(defaultBankCountry("Atlantic/Azores", ["en-US"])).toBe("PT");
    expect(defaultBankCountry("Europe/London", ["en-US"])).toBe("GB");
    expect(defaultBankCountry("Europe/Warsaw", ["en-US"])).toBe("PL");
  });

  it("includes every mapped timezone country in BANK_COUNTRIES", () => {
    expect(BANK_COUNTRIES).toContain(defaultBankCountry("Europe/Stockholm", []));
    expect(BANK_COUNTRIES).toContain(defaultBankCountry("Europe/Prague", []));
  });

  it("falls back to supported language regions when the timezone is UTC or missing", () => {
    expect(defaultBankCountry("UTC", ["en-US", "es-ES"])).toBe("ES");
    expect(defaultBankCountry("UTC", ["en-US", "nl-NL"])).toBe("NL");
    expect(defaultBankCountry("Etc/UTC", ["en-GB"])).toBe("GB");
    expect(defaultBankCountry("", ["fr-BE"])).toBe("BE");
  });

  it("maximizes bare language tags only when the timezone gives no location", () => {
    expect(defaultBankCountry("UTC", ["nl"])).toBe("NL");
    expect(defaultBankCountry("UTC", ["de"])).toBe("DE");
    expect(defaultBankCountry("America/Sao_Paulo", ["nl"])).toBe("ES");
  });

  it("falls back to ES for unsupported locations and ignores malformed locales", () => {
    expect(defaultBankCountry("America/New_York", ["en-US"])).toBe("ES");
    expect(defaultBankCountry("America/Argentina/Buenos_Aires", ["es-AR"])).toBe("ES");
    expect(defaultBankCountry("UTC", ["not_a_valid_locale_tag_@@@", "it-IT"])).toBe("IT");
    expect(defaultBankCountry("", [])).toBe("ES");
  });
});
