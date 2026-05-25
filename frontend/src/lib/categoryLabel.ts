import type { TFunction } from "i18next";
import type { Category } from "@/api/catalog";

/**
 * Display name for a category. For globals the server returns the i18n key
 * (e.g. "category.home.rent") in `name`, so it falls through to translation.
 * For customs the server returns the owner-typed text, which is shown as-is.
 */
export function categoryLabel(c: Pick<Category, "name" | "custom" | "code">, t: TFunction): string {
  if (c.custom) return c.name;
  return t(c.name ?? `category.${c.code}`);
}

/**
 * Convenience for callsites that only have a category code (e.g. analytics rows).
 * Looks up the matching entry in the categories list; falls back to translating
 * the raw `category.<code>` key when the code isn't found (safe for globals).
 */
export function categoryLabelByCode(code: string, categories: Category[], t: TFunction): string {
  const found = categories.find((c) => c.code === code);
  if (found) return categoryLabel(found, t);
  return t(`category.${code}`);
}
