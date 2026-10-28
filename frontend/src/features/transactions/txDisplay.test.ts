import { describe, expect, it } from "vitest";
import Decimal from "decimal.js";
import { formatSignedMoney, formatTxAmount, isPositiveDisplay, signedTxAmount } from "./txDisplay";

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

describe("formatSignedMoney", () => {
  it("adds a plus to money in; the minus belongs to the number", () => {
    expect(formatSignedMoney(new Decimal("100"), "EUR", "en")).toBe("+€100.00");
    expect(formatSignedMoney(new Decimal("-40"), "EUR", "en")).toBe("-€40.00");
    expect(formatSignedMoney(new Decimal("0"), "EUR", "en")).toBe("€0.00");
  });
});

describe("formatTxAmount", () => {
  it("is the row's signed amount, refunds positive", () => {
    expect(formatTxAmount(income, "EUR", "en")).toBe("+€100.00");
    expect(formatTxAmount(expense, "EUR", "en")).toBe("-€40.00");
    expect(formatTxAmount(refund, "EUR", "en")).toBe("+€30.00");
  });
});
