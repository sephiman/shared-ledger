import { describe, expect, it } from "vitest";
import {
  categoryDiffers,
  isNegativeAmount,
  isPositiveAmount,
  negatedAmount,
  overRefundWarning,
  refundAmountValid,
} from "./refundDraft";

describe("negatedAmount", () => {
  it("flips the sign to the cent", () => {
    expect(negatedAmount("30")).toBe("-30.00");
    expect(negatedAmount("-30.00")).toBe("30.00");
    expect(negatedAmount("12,5")).toBe("-12.50");
  });

  it("leaves half-typed or zero input alone", () => {
    expect(negatedAmount("")).toBe("");
    expect(negatedAmount("-")).toBe("-");
    expect(negatedAmount("0")).toBe("0");
  });
});

describe("refundAmountValid", () => {
  it("accepts only a negative amount", () => {
    expect(refundAmountValid("-30.00")).toBe(true);
    expect(refundAmountValid("30.00")).toBe(false);
    expect(refundAmountValid("0")).toBe(false);
    expect(refundAmountValid("")).toBe(false);
    expect(refundAmountValid("abc")).toBe(false);
  });

  it("recognises which way an amount points, so the toggle can flip it", () => {
    expect(isPositiveAmount("30")).toBe(true);
    expect(isPositiveAmount("-30")).toBe(false);
    expect(isNegativeAmount("-30")).toBe(true);
    expect(isNegativeAmount("30")).toBe(false);
    // Nothing to flip either way.
    expect(isPositiveAmount("")).toBe(false);
    expect(isNegativeAmount("")).toBe(false);
    expect(isPositiveAmount("0")).toBe(false);
    expect(isNegativeAmount("0")).toBe(false);
  });
});

describe("categoryDiffers", () => {
  it("only flags a real disagreement with a linked original", () => {
    expect(categoryDiffers("groceries", { categoryCode: "groceries" })).toBe(false);
    expect(categoryDiffers("fuel", { categoryCode: "groceries" })).toBe(true);
    expect(categoryDiffers("fuel", null)).toBe(false);
    expect(categoryDiffers("", { categoryCode: "groceries" })).toBe(false);
  });
});

describe("overRefundWarning", () => {
  const original = { amount: "80.00", refundedTotal: null };

  it("stays quiet for a partial refund", () => {
    expect(overRefundWarning(original, "-30.00")).toBeNull();
  });

  it("stays quiet when the refunds add up to exactly the purchase", () => {
    expect(overRefundWarning({ amount: "80.00", refundedTotal: "-50.00" }, "-30.00")).toBeNull();
  });

  it("warns once the refunds would pass what was paid", () => {
    const warning = overRefundWarning({ amount: "80.00", refundedTotal: "-60.00" }, "-30.00");
    expect(warning?.refunded.toFixed(2)).toBe("90.00");
    expect(warning?.original.toFixed(2)).toBe("80.00");
  });

  it("has nothing to say without an original or an amount", () => {
    expect(overRefundWarning(null, "-30.00")).toBeNull();
    expect(overRefundWarning(original, "")).toBeNull();
  });
});
