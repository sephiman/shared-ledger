import Decimal from "decimal.js";

/** The arithmetic behind the refund forms: the sign a refund has to carry, and whether the refunds linked
 *  to a purchase have started to exceed what was paid for it. Kept out of the components so the rules can
 *  be tested without rendering a dialog. */
const SCALE = 2;
const ROUNDING = Decimal.ROUND_HALF_EVEN;

export interface RefundTarget {
  /** The original's own (positive) amount. */
  amount: string;
  /** What has already come back for it, negative, or null when nothing has. */
  refundedTotal: string | null;
}

export interface OverRefundWarning {
  /** Everything refunded once this draft is saved, as a positive magnitude. */
  refunded: Decimal;
  /** The original purchase, as a positive magnitude. */
  original: Decimal;
}

function parse(raw: string): Decimal | null {
  const text = raw.trim().replace(",", ".");
  if (text === "") return null;
  try {
    const value = new Decimal(text);
    return value.isFinite() ? value : null;
  } catch {
    return null;
  }
}

/** Flips the sign of what the user typed, keeping it to the cent. Blank or unparseable text is left
 *  alone so a half-typed amount is never rewritten. */
export function negatedAmount(raw: string): string {
  const value = parse(raw);
  if (value === null || value.isZero()) return raw;
  return value.negated().toDecimalPlaces(SCALE, ROUNDING).toFixed(SCALE);
}

/** A refund is money coming back: negative, and never zero. */
export function refundAmountValid(raw: string): boolean {
  const value = parse(raw);
  return value !== null && value.isNegative();
}

export function isPositiveAmount(raw: string): boolean {
  const value = parse(raw);
  return value !== null && value.gt(0);
}

export function isNegativeAmount(raw: string): boolean {
  const value = parse(raw);
  return value !== null && value.isNegative();
}

/** True when the refund is being filed under a different category than the purchase it nets — allowed, but
 *  worth saying out loud, since the original's category is then not the one that gets the money back. */
export function categoryDiffers(categoryCode: string, original: { categoryCode: string } | null): boolean {
  if (!original || !categoryCode) return false;
  return categoryCode !== original.categoryCode;
}

/** Non-blocking: partial and repeated refunds are normal, so this only reports when the total handed back
 *  would pass what was paid. Null when there is nothing to compare against. */
export function overRefundWarning(original: RefundTarget | null, draftAmount: string): OverRefundWarning | null {
  if (!original) return null;
  const draft = parse(draftAmount);
  if (draft === null) return null;
  const already = new Decimal(original.refundedTotal || 0).abs();
  const refunded = already.plus(draft.abs()).toDecimalPlaces(SCALE, ROUNDING);
  const paid = new Decimal(original.amount || 0).abs();
  return refunded.gt(paid) ? { refunded, original: paid } : null;
}
