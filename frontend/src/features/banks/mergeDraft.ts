import Decimal from "decimal.js";
import type { Direction } from "@/api/banks";

/** Merge arithmetic and prefills. The amount is derived, never typed — a merge nets what the bank already
 *  charged — so this only has to net exactly (HALF_EVEN at 2dp, as the backend's `Money.normalize`) and
 *  reproduce the server's defaults, keeping the dialog honest about what confirming will write. */
const SCALE = 2;
const ROUNDING = Decimal.ROUND_HALF_EVEN;

/** The backend caps a transaction description at 500 chars; a long prefill would otherwise 400. */
export const DESCRIPTION_MAX = 500;

/** One selected inbox row, already resolved through the inbox's draft overrides. */
export interface MergeSource {
  id: string;
  bookingDate: string;
  amount: string;
  /** The row's resolved direction — flipping it in the inbox flips this item's sign in the net. */
  direction: Direction;
  counterparty: string | null;
  /** The row's resolved category ("" when none), used only to decide the dialog's prefill. */
  categoryCode: string;
  /** The row's resolved description — what a plain confirm would have written. */
  description: string;
  /** Connection · account, so a merge across banks shows where each item came from. */
  sourceLabel: string;
  possibleDuplicate: boolean;
}

/** One term of the visible arithmetic. [operator] is what precedes the amount, so a leading negative reads
 *  "−€9.03" and the ones after it join as " + €7.78" / " − €7.50" — never "+ −€7.50". */
export interface NettingTerm {
  operator: "" | "+" | "−";
  absolute: string;
}

export interface MergeValidation {
  categoryMissing: boolean;
  dateMissing: boolean;
  ok: boolean;
}

/** Two items is the floor; direction no longer matters, since mixed selections net. */
export function canMerge(items: MergeSource[]): boolean {
  return items.length >= 2;
}

/** Booking date ascending, id as tiebreak — the order the server merges in, so the joined description and
 *  the listed items read the same here and there. */
export function sortSources(items: MergeSource[]): MergeSource[] {
  return [...items].sort((a, b) => a.bookingDate.localeCompare(b.bookingDate) || a.id.localeCompare(b.id));
}

/** Incomes add, expenses subtract. */
export function signedAmount(item: MergeSource): Decimal {
  const amount = new Decimal(item.amount || 0);
  return item.direction === "income" ? amount : amount.negated();
}

const round = (value: Decimal): Decimal => value.toDecimalPlaces(SCALE, ROUNDING);

function net(items: MergeSource[]): Decimal {
  return round(items.reduce((sum, item) => sum.plus(signedAmount(item)), new Decimal(0)));
}

/** The net with its sign, e.g. "-1.25" — what the arithmetic line ends on. */
export function netTotal(items: MergeSource[]): string {
  return net(items).toFixed(SCALE);
}

/** The magnitude the transaction will carry (amounts are always positive; direction carries the sign). */
export function netAbsolute(items: MergeSource[]): string {
  return net(items).abs().toFixed(SCALE);
}

/** The direction the transaction will carry, or null when the items cancel out and there is none. */
export function netDirection(items: MergeSource[]): Direction | null {
  const sign = net(items).comparedTo(0);
  if (sign === 0) return null;
  return sign > 0 ? "income" : "expense";
}

export function isZeroNet(items: MergeSource[]): boolean {
  return net(items).isZero();
}

export function nettingTerms(items: MergeSource[]): NettingTerm[] {
  return sortSources(items).map((item, index) => {
    const value = round(signedAmount(item));
    const negative = value.isNegative();
    return {
      operator: index === 0 ? (negative ? "−" : "") : negative ? "−" : "+",
      absolute: value.abs().toFixed(SCALE),
    };
  });
}

/** ISO dates compare correctly as plain strings. */
export function earliestDate(items: MergeSource[]): string {
  return items.reduce((min, item) => (min === "" || item.bookingDate < min ? item.bookingDate : min), "");
}

/** Prefills the one category the selection points at. Uncategorised items don't count against it — a
 *  categorised charge merged with an unsuggested refund still knows where it belongs — but two *different*
 *  categories are the user's call. The winner must also fit the netted direction: a net-expense merge must
 *  not open with an income category the server would reject. */
export function prefillCategory(items: MergeSource[], allowedCodes: Iterable<string>): string {
  const suggested = [...new Set(items.map((item) => item.categoryCode).filter(Boolean))];
  if (suggested.length !== 1) return "";
  return new Set(allowedCodes).has(suggested[0]) ? suggested[0] : "";
}

/** "A + B", in merge order, deduped — the same fallback the server would apply to a blank description. */
export function prefillDescription(items: MergeSource[]): string {
  const parts = sortSources(items).map((item) => item.description.trim()).filter(Boolean);
  return [...new Set(parts)].join(" + ").slice(0, DESCRIPTION_MAX);
}

export function anyPossibleDuplicate(items: MergeSource[]): boolean {
  return items.some((item) => item.possibleDuplicate);
}

export function validateMerge(draft: { categoryCode: string; date: string }): MergeValidation {
  const categoryMissing = !draft.categoryCode;
  const dateMissing = !draft.date;
  return { categoryMissing, dateMissing, ok: !categoryMissing && !dateMissing };
}
