import { describe, expect, it } from "vitest";
import { displaySign, isPositiveDisplay, signedTxAmount } from "./txDisplay";

const income = { direction: "income" as const, amount: "100.00" };
const expense = { direction: "expense" as const, amount: "40.00" };
const refund = { direction: "expense" as const, amount: "-30.00" };

describe("signedTxAmount", () => {
  it("signs by direction", () => {
    expect(signedTxAmount(income).toFixed(2)).toBe("100.00");
    expect(signedTxAmount(expense).toFixed(2)).toBe("-40.00");
  });

  it("reads a refund as money coming back, not as a double negative", () => {
    expect(signedTxAmount(refund).toFixed(2)).toBe("30.00");
  });
});

describe("isPositiveDisplay", () => {
  it("is true for income and refunds, false for ordinary expenses", () => {
    expect(isPositiveDisplay(income)).toBe(true);
    expect(isPositiveDisplay(refund)).toBe(true);
    expect(isPositiveDisplay(expense)).toBe(false);
  });
});

describe("displaySign", () => {
  it("only ever adds a plus — the minus belongs to the number", () => {
    expect(displaySign(income)).toBe("+");
    expect(displaySign(refund)).toBe("+");
    expect(displaySign(expense)).toBe("");
  });
});
