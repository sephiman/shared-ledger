import type { TFunction } from "i18next";
import type { Category } from "@/api/catalog";

/** Display name for a category. Globals carry the i18n key (e.g. "category.home.rent") in `name` and fall
 *  through to translation; customs carry owner-typed text shown as-is. */
export function categoryLabel(c: Pick<Category, "name" | "custom" | "code">, t: TFunction): string {
  if (c.custom) return c.name;
  return t(c.name ?? `category.${c.code}`);
}

/** For callsites holding only a category code (e.g. analytics rows). Falls back to translating the raw
 *  `category.<code>` key when the code isn't in the list. */
export function categoryLabelByCode(code: string, categories: Category[], t: TFunction): string {
  const found = categories.find((c) => c.code === code);
  if (found) return categoryLabel(found, t);
  return t(`category.${code}`);
}
