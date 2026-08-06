import { describe, expect, it } from "vitest";
import {
  addRow,
  initialRows,
  parseAmount,
  percentDisplay,
  remainderOf,
  removeRow,
  setAmount,
  setPercent,
  toSplitParts,
  updateRow,
  validate,
  type SplitRow,
} from "./splitDraft";

const rows = (...amounts: string[]): SplitRow[] =>
  amounts.map((amount, i) => ({
    key: `p${i + 1}`,
    amount,
    pctDraft: null,
    categoryCode: "groceries",
    description: "MERCADONA",
  }));

describe("initialRows", () => {
  it("opens on two halves that add up exactly", () => {
    const r = initialRows("42.00", "groceries", "MERCADONA");
    expect(r.map((x) => x.amount)).toEqual(["21.00", "21.00"]);
    expect(validate(r, "42.00").ok).toBe(true);
  });

  it("puts the odd cent on the second row rather than losing it", () => {
    const r = initialRows("10.01", "groceries", "MERCADONA");
    expect(remainderOf(r, "10.01").toFixed(2)).toBe("0.00");
    expect(validate(r, "10.01").ok).toBe(true);
  });

  it("seeds category and description on both rows", () => {
    expect(initialRows("10.00", "fuel", "REPSOL").map((x) => [x.categoryCode, x.description])).toEqual([
      ["fuel", "REPSOL"],
      ["fuel", "REPSOL"],
    ]);
  });
});

describe("two-row auto-balance", () => {
  it("moves the remainder to the other row when an amount is edited", () => {
    const r = setAmount(rows("21.00", "21.00"), 0, "30.00", "42.00");
    expect(r.map((x) => x.amount)).toEqual(["30.00", "12.00"]);
    expect(validate(r, "42.00").ok).toBe(true);
  });

  it("balances from either side", () => {
    const r = setAmount(rows("21.00", "21.00"), 1, "2.50", "42.00");
    expect(r.map((x) => x.amount)).toEqual(["39.50", "2.50"]);
  });

  it("keeps the typed text verbatim so mid-typing isn't rewritten", () => {
    const r = setAmount(rows("21.00", "21.00"), 0, "5,", "42.00");
    expect(r[0].amount).toBe("5,");
    // "5," parses as 5, so the sibling is already balanced against it.
    expect(r[1].amount).toBe("37.00");
  });

  it("accepts a decimal comma", () => {
    expect(setAmount(rows("21.00", "21.00"), 0, "12,34", "42.00")[1].amount).toBe("29.66");
  });
});

describe("percentages", () => {
  it("derives the amount from a percentage, rounded to the cent", () => {
    const r = setPercent(rows("5.00", "5.00"), 0, "33.33", "10.00");
    expect(r[0].amount).toBe("3.33");
    // The leftover goes to the auto-balanced sibling, so the pair still matches to the cent.
    expect(r[1].amount).toBe("6.67");
    expect(validate(r, "10.00").ok).toBe(true);
  });

  it("shows the in-progress percentage text, then re-derives it from the amount", () => {
    const typed = setPercent(rows("5.00", "5.00"), 0, "33.33", "10.00");
    expect(percentDisplay(typed[0], "10.00")).toBe("33.33");
    // The sibling was recomputed, so its percentage comes from its amount — not from 100 − 33.33.
    expect(percentDisplay(typed[1], "10.00")).toBe("66.70");
  });

  it("derives from the amount, never compounding percent-of-percent", () => {
    // A third of €10 stored as €3.33 reads back as 33.30 %, not 33.33 % — the euro value is the truth.
    const r = updateRow(rows("3.33", "6.67"), 0, { pctDraft: null });
    expect(percentDisplay(r[0], "10.00")).toBe("33.30");
    expect(percentDisplay(r[1], "10.00")).toBe("66.70");
  });

  it("is blank when there is no amount to derive from", () => {
    expect(percentDisplay(rows("")[0], "10.00")).toBe("");
    expect(percentDisplay(rows("5.00")[0], "0.00")).toBe("");
  });
});

describe("three or more rows", () => {
  it("does not auto-balance — the edit stands alone", () => {
    const r = setAmount(rows("10.00", "10.00", "10.00"), 0, "5.00", "30.00");
    expect(r.map((x) => x.amount)).toEqual(["5.00", "10.00", "10.00"]);
  });

  it("surfaces the shortfall and blocks saving", () => {
    const r = setAmount(rows("10.00", "10.00", "10.00"), 0, "5.00", "30.00");
    const v = validate(r, "30.00");
    expect(v.remainder).toBe("5.00");
    expect(v.showRemainder).toBe(true);
    expect(v.balanced).toBe(false);
    expect(v.ok).toBe(false);
    // Nothing is wrong with any individual row; only the total is off.
    expect(v.rowErrors).toEqual([null, null, null]);
  });

  it("reports going over the total as a negative remainder", () => {
    const v = validate(rows("10.00", "10.00", "15.00"), "30.00");
    expect(v.remainder).toBe("-5.00");
    expect(v.balanced).toBe(false);
  });

  it("surfaces a leftover cent from percentage entry instead of absorbing it", () => {
    // 33.33 % three times over €10.00 is €9.99 — the missing cent has to be visible.
    let r = rows("", "", "");
    r = setPercent(r, 0, "33.33", "10.00");
    r = setPercent(r, 1, "33.33", "10.00");
    r = setPercent(r, 2, "33.33", "10.00");
    expect(r.map((x) => x.amount)).toEqual(["3.33", "3.33", "3.33"]);
    const v = validate(r, "10.00");
    expect(v.remainder).toBe("0.01");
    expect(v.ok).toBe(false);
  });

  it("accepts an exact three-way split", () => {
    expect(validate(rows("3.34", "3.33", "3.33"), "10.00").ok).toBe(true);
  });
});

describe("row errors", () => {
  it("blames the row auto-balanced to nothing, not the whole dialog", () => {
    const r = setAmount(rows("5.00", "5.00"), 0, "15.00", "10.00");
    expect(r[1].amount).toBe("-5.00");
    const v = validate(r, "10.00");
    expect(v.rowErrors).toEqual([null, "amount_nothing_left"]);
    expect(v.ok).toBe(false);
    // Auto-balance keeps the sum exact, so the remainder line would say "balanced" — hence it's hidden
    // for two rows and the row error carries the message.
    expect(v.balanced).toBe(true);
    expect(v.showRemainder).toBe(false);
  });

  it("treats a row taking exactly the whole total the same way", () => {
    const v = validate(setAmount(rows("5.00", "5.00"), 0, "10.00", "10.00"), "10.00");
    expect(v.rowErrors).toEqual([null, "amount_nothing_left"]);
  });

  it("blames the row itself for unparseable text", () => {
    const v = validate(setAmount(rows("5.00", "5.00"), 0, "abc", "10.00"), "10.00");
    expect(v.rowErrors[0]).toBe("amount_invalid");
  });

  it("rejects a third decimal", () => {
    expect(validate(rows("3.333", "6.667"), "10.00").rowErrors[0]).toBe("amount_invalid");
  });

  it("requires a category on every row", () => {
    const r = updateRow(rows("5.00", "5.00"), 1, { categoryCode: "" });
    const v = validate(r, "10.00");
    expect(v.rowErrors).toEqual([null, "category_missing"]);
    expect(v.ok).toBe(false);
  });
});

describe("add and remove", () => {
  it("appends an empty row seeded with the item's category and description", () => {
    const r = addRow(rows("5.00", "5.00"), "fuel", "REPSOL");
    expect(r).toHaveLength(3);
    expect(r[2]).toMatchObject({ amount: "", categoryCode: "fuel", description: "REPSOL" });
    // The new row leaves the total short rather than silently reshuffling the existing amounts.
    expect(validate(r, "10.00").remainder).toBe("0.00");
    expect(validate(r, "10.00").ok).toBe(false);
  });

  it("gives new rows keys that don't collide with removed ones", () => {
    const three = addRow(rows("5.00", "5.00"), "fuel", "");
    const afterRemove = removeRow(three, 1);
    expect(addRow(afterRemove, "fuel", "").map((x) => x.key)).toEqual(["p1", "p3", "p4"]);
  });

  it("refuses to drop below two rows", () => {
    const two = rows("5.00", "5.00");
    expect(removeRow(two, 0)).toBe(two);
    expect(removeRow(addRow(two, "fuel", ""), 2)).toHaveLength(2);
  });
});

describe("toSplitParts", () => {
  it("normalises amounts and nulls blank descriptions for the bank fallback", () => {
    const r = updateRow(rows("5,5", "4.50"), 0, { description: "  " });
    expect(toSplitParts(r)).toEqual([
      { amount: "5.50", categoryCode: "groceries", description: null },
      { amount: "4.50", categoryCode: "groceries", description: "MERCADONA" },
    ]);
  });
});

describe("parseAmount", () => {
  it("returns null for anything unusable", () => {
    expect(parseAmount("")).toBeNull();
    expect(parseAmount("  ")).toBeNull();
    expect(parseAmount("abc")).toBeNull();
    expect(parseAmount("1.2.3")).toBeNull();
  });
});
