import { describe, expect, it } from "vitest";
import {
  anyPossibleDuplicate,
  canMerge,
  DESCRIPTION_MAX,
  earliestDate,
  isZeroNet,
  netAbsolute,
  netDirection,
  nettingTerms,
  netTotal,
  prefillCategory,
  prefillDescription,
  signedAmount,
  sortSources,
  validateMerge,
  type MergeSource,
} from "./mergeDraft";

const source = (patch: Partial<MergeSource> = {}): MergeSource => ({
  id: "a",
  bookingDate: "2026-08-10",
  amount: "10.00",
  direction: "expense",
  counterparty: "TAPAS",
  categoryCode: "restaurants",
  description: "TAPAS",
  sourceLabel: "ING · Main",
  possibleDuplicate: false,
  ...patch,
});

describe("canMerge", () => {
  it("needs at least two items, whatever their directions", () => {
    expect(canMerge([])).toBe(false);
    expect(canMerge([source()])).toBe(false);
    expect(canMerge([source(), source({ id: "b" })])).toBe(true);
    expect(canMerge([source(), source({ id: "b", direction: "income" })])).toBe(true);
  });
});

describe("signedAmount", () => {
  it("adds incomes and subtracts expenses", () => {
    expect(signedAmount(source({ amount: "9.03", direction: "expense" })).toFixed(2)).toBe("-9.03");
    expect(signedAmount(source({ amount: "7.78", direction: "income" })).toFixed(2)).toBe("7.78");
  });
});

describe("netTotal", () => {
  it("sums same-way items to the cent", () => {
    expect(netTotal([source({ amount: "12.50" }), source({ amount: "7.50" })])).toBe("-20.00");
    expect(netTotal([source({ amount: "3.33" }), source({ amount: "3.33" }), source({ amount: "3.34" })])).toBe("-10.00");
  });

  it("nets a charge against its partial refund", () => {
    expect(netTotal([
      source({ amount: "9.03", direction: "expense" }),
      source({ id: "b", amount: "7.78", direction: "income" }),
    ])).toBe("-1.25");
  });

  it("does not drift the way floats would", () => {
    expect(netTotal([
      source({ amount: "0.10", direction: "income" }),
      source({ id: "b", amount: "0.20", direction: "income" }),
      source({ id: "c", amount: "0.01", direction: "income" }),
    ])).toBe("0.31");
  });

  it("is zero when the items cancel out", () => {
    expect(netTotal([
      source({ amount: "25.00", direction: "expense" }),
      source({ id: "b", amount: "25.00", direction: "income" }),
    ])).toBe("0.00");
  });
});

describe("netDirection and netAbsolute", () => {
  const charge = source({ amount: "9.03", direction: "expense" });
  const refund = source({ id: "b", amount: "7.78", direction: "income" });

  it("takes the direction from the sign and the amount from the magnitude", () => {
    expect(netDirection([charge, refund])).toBe("expense");
    expect(netAbsolute([charge, refund])).toBe("1.25");
    const payout = [source({ amount: "30.00", direction: "income" }), source({ id: "b", amount: "12.00" })];
    expect(netDirection(payout)).toBe("income");
    expect(netAbsolute(payout)).toBe("18.00");
  });

  it("has no direction when the items cancel out", () => {
    const cancelling = [source({ amount: "25.00" }), source({ id: "b", amount: "25.00", direction: "income" })];
    expect(netDirection(cancelling)).toBeNull();
    expect(isZeroNet(cancelling)).toBe(true);
    expect(isZeroNet([charge, refund])).toBe(false);
  });
});

describe("nettingTerms", () => {
  it("reads as arithmetic: a leading minus, then + or − per term", () => {
    expect(nettingTerms([
      source({ id: "a", bookingDate: "2026-08-09", amount: "9.03", direction: "expense" }),
      source({ id: "b", bookingDate: "2026-08-11", amount: "7.78", direction: "income" }),
    ])).toEqual([
      { operator: "−", absolute: "9.03" },
      { operator: "+", absolute: "7.78" },
    ]);
  });

  it("subtracts rather than adding a negative for an all-expense merge", () => {
    expect(nettingTerms([
      source({ id: "a", bookingDate: "2026-08-09", amount: "42.50" }),
      source({ id: "b", bookingDate: "2026-08-11", amount: "7.50" }),
    ])).toEqual([
      { operator: "−", absolute: "42.50" },
      { operator: "−", absolute: "7.50" },
    ]);
  });

  it("leaves a leading income unsigned", () => {
    expect(nettingTerms([source({ amount: "30.00", direction: "income" })])[0].operator).toBe("");
  });
});

describe("earliestDate", () => {
  it("takes the minimum whatever the input order", () => {
    expect(earliestDate([
      source({ bookingDate: "2026-08-11" }),
      source({ bookingDate: "2026-07-30" }),
      source({ bookingDate: "2026-08-02" }),
    ])).toBe("2026-07-30");
  });
});

describe("sortSources", () => {
  it("orders by booking date, then id", () => {
    const sorted = sortSources([
      source({ id: "c", bookingDate: "2026-08-11" }),
      source({ id: "b", bookingDate: "2026-08-10" }),
      source({ id: "a", bookingDate: "2026-08-10" }),
    ]);
    expect(sorted.map((s) => s.id)).toEqual(["a", "b", "c"]);
  });
});

describe("prefillCategory", () => {
  const allowed = ["restaurants", "fuel"];

  it("uses the shared suggestion when every item agrees", () => {
    expect(prefillCategory([source(), source({ id: "b" })], allowed)).toBe("restaurants");
  });

  it("still uses it when the other items carry no category at all", () => {
    expect(prefillCategory([source({ categoryCode: "" }), source({ id: "b" })], allowed)).toBe("restaurants");
    expect(prefillCategory([source({ categoryCode: "" }), source({ id: "b", categoryCode: "" })], allowed)).toBe("");
  });

  it("leaves the picker empty when two items disagree", () => {
    expect(prefillCategory([source(), source({ id: "b", categoryCode: "fuel" })], allowed)).toBe("");
  });

  it("drops a shared category that doesn't fit the netted direction", () => {
    expect(prefillCategory([source(), source({ id: "b" })], ["salary", "refunds"])).toBe("");
  });
});

describe("prefillDescription", () => {
  it("joins the descriptions in merge order", () => {
    expect(prefillDescription([
      source({ id: "b", bookingDate: "2026-08-11", description: "TIP" }),
      source({ id: "a", bookingDate: "2026-08-10", description: "TAPAS" }),
    ])).toBe("TAPAS + TIP");
  });

  it("skips blanks and repeats", () => {
    expect(prefillDescription([
      source({ id: "a", description: "TAPAS" }),
      source({ id: "b", description: "  " }),
      source({ id: "c", description: "TAPAS" }),
    ])).toBe("TAPAS");
  });

  it("stays within the description the backend accepts", () => {
    const long = Array.from({ length: 20 }, (_, i) => source({ id: `i${i}`, description: "x".repeat(60) }));
    expect(prefillDescription(long).length).toBeLessThanOrEqual(DESCRIPTION_MAX);
  });
});

describe("anyPossibleDuplicate", () => {
  it("is true as soon as one item carries the flag", () => {
    expect(anyPossibleDuplicate([source(), source({ id: "b" })])).toBe(false);
    expect(anyPossibleDuplicate([source(), source({ id: "b", possibleDuplicate: true })])).toBe(true);
  });
});

describe("validateMerge", () => {
  it("requires a category and a date", () => {
    expect(validateMerge({ categoryCode: "", date: "2026-08-10" })).toMatchObject({ categoryMissing: true, ok: false });
    expect(validateMerge({ categoryCode: "restaurants", date: "" })).toMatchObject({ dateMissing: true, ok: false });
    expect(validateMerge({ categoryCode: "restaurants", date: "2026-08-10" }).ok).toBe(true);
  });
});
