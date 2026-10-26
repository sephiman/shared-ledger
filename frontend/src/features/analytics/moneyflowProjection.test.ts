import { describe, expect, it } from "vitest";
import type { MoneyFlowNode, MoneyFlowProjectionResponse, ProjectedFlowComponents } from "@/api/analytics";
import { parseForecastWindow } from "./forecastWindow";
import type { SankeyNodeDatum } from "./moneyflow";
import {
  basisMonths,
  compactMonthSpan,
  componentsLookup,
  hasEstimatedPart,
  isCurrentMonthScope,
  monthList,
  modeSettingsOrder,
  presentDisabledKeys,
  withActualsOnly,
} from "./moneyflowProjection";

function projection(partial: Partial<MoneyFlowProjectionResponse>): MoneyFlowProjectionResponse {
  return {
    year: 2026,
    month: 10,
    level: "group",
    dayOfMonth: 18,
    daysInMonth: 31,
    daysRemaining: 13,
    windowMonths: 3,
    monthsAvailable: 3,
    basisFromYear: 2026,
    basisFromMonth: 7,
    basisToYear: 2026,
    basisToMonth: 9,
    income: "0.00",
    expenses: "0.00",
    saved: "0.00",
    deficit: "0.00",
    nodes: [],
    links: [],
    components: [],
    ...partial,
  };
}

const datum = (id: string, side: SankeyNodeDatum["side"]): SankeyNodeDatum => ({
  name: id,
  id,
  side,
  column: "right",
  groupCode: null,
  amount: 1,
  share: null,
  shareOf: null,
  fill: "#000",
});

const components = (side: "income" | "expense", id: string, recurringDue = "0.00", discretionaryEstimate = "0.00"): ProjectedFlowComponents => ({
  side,
  id,
  actual: "10.00",
  recurringDue,
  discretionaryEstimate,
  projected: "10.00",
  projectionMode: side === "income" ? null : "ENVELOPE",
});

describe("isCurrentMonthScope", () => {
  const today = new Date(2026, 9, 18);

  it("is true only for the running month", () => {
    expect(isCurrentMonthScope({ scope: "month", year: 2026, month: 10 }, today)).toBe(true);
    expect(isCurrentMonthScope({ scope: "month", year: 2026, month: 9 }, today)).toBe(false);
    expect(isCurrentMonthScope({ scope: "month", year: 2025, month: 10 }, today)).toBe(false);
  });

  it("is false for ranges even when they include the running month", () => {
    expect(isCurrentMonthScope({ scope: "ytd", year: 2026, month: 10 }, today)).toBe(false);
    expect(isCurrentMonthScope({ scope: "trailing12", year: 2026, month: 10 }, today)).toBe(false);
  });
});

describe("basisMonths", () => {
  it("lists the covered months across a year boundary", () => {
    const r = projection({ basisFromYear: 2025, basisFromMonth: 11, basisToYear: 2026, basisToMonth: 1 });
    expect(basisMonths(r)).toEqual([
      { year: 2025, month: 11 },
      { year: 2025, month: 12 },
      { year: 2026, month: 1 },
    ]);
  });

  it("is empty without history", () => {
    expect(basisMonths(projection({ monthsAvailable: 0, basisFromYear: null, basisFromMonth: null, basisToYear: null, basisToMonth: null })))
      .toEqual([]);
  });
});

describe("month labels", () => {
  const jul = { year: 2026, month: 7 };
  const aug = { year: 2026, month: 8 };
  const sep = { year: 2026, month: 9 };

  it("spans months of one year with the year once", () => {
    expect(compactMonthSpan([jul, aug, sep], "en")).toBe("Jul–Sep 2026");
    expect(monthList([jul, aug, sep], "en")).toBe("Jul, Aug, Sep 2026");
  });

  it("names both years across a boundary", () => {
    const months = [{ year: 2025, month: 12 }, { year: 2026, month: 1 }];
    expect(compactMonthSpan(months, "en")).toBe("Dec 2025–Jan 2026");
    expect(monthList(months, "en")).toBe("Dec 2025, Jan 2026");
  });

  it("names a single month on its own", () => {
    expect(compactMonthSpan([sep], "en")).toBe("Sep 2026");
  });
});

describe("componentsLookup", () => {
  const lookup = componentsLookup([components("expense", "home"), components("income", "income.salary")]);

  it("finds a node's estimate by side and id", () => {
    expect(lookup(datum("home", "expense"))?.id).toBe("home");
    expect(lookup(datum("income.salary", "income"))?.side).toBe("income");
  });

  it("has nothing for structural nodes", () => {
    expect(lookup(datum("hub", "hub"))).toBeNull();
    expect(lookup(datum("__others_expense__", "others"))).toBeNull();
    expect(lookup(datum("home", "income"))).toBeNull();
  });
});

describe("hasEstimatedPart", () => {
  it("is false once everything has already happened", () => {
    expect(hasEstimatedPart(components("expense", "home"))).toBe(false);
    expect(hasEstimatedPart(components("expense", "home", "350.00"))).toBe(true);
    expect(hasEstimatedPart(components("expense", "home", "0.00", "38.84"))).toBe(true);
  });
});

describe("parseForecastWindow", () => {
  it("accepts only the offered windows and falls back to three months", () => {
    expect(parseForecastWindow("6")).toBe(6);
    expect(parseForecastWindow("12")).toBe(12);
    expect(parseForecastWindow("5")).toBe(3);
    expect(parseForecastWindow(null)).toBe(3);
  });
});

describe("withActualsOnly", () => {
  const node = (id: string, side: MoneyFlowNode["side"], amount: string): MoneyFlowNode => ({
    id,
    side,
    groupCode: side === "expense" ? id : null,
    amount,
  });
  const part = (
    side: "income" | "expense",
    id: string,
    actual: string,
    recurringDue: string,
    discretionaryEstimate: string,
    projected: string,
  ): ProjectedFlowComponents => ({
    side,
    id,
    actual,
    recurringDue,
    discretionaryEstimate,
    projected,
    projectionMode: side === "income" ? null : "ENVELOPE",
  });

  // Income 3000 (2500 actual + 500 still due); home 1890.12 + 350 + 38.84; leisure 0 + 0 + 120.40.
  const base = projection({
    income: "3000.00",
    expenses: "2399.36",
    saved: "600.64",
    deficit: "0.00",
    nodes: [
      node("income.salary", "income", "3000.00"),
      node("hub", "hub", "3000.00"),
      node("home", "expense", "2278.96"),
      node("leisure", "expense", "120.40"),
      node("saved", "saved", "600.64"),
    ],
    components: [
      part("income", "income.salary", "2500.00", "500.00", "0.00", "3000.00"),
      part("expense", "home", "1890.12", "350.00", "38.84", "2278.96"),
      part("expense", "leisure", "0.00", "0.00", "120.40", "120.40"),
    ],
  });
  const amounts = (r: MoneyFlowProjectionResponse) => Object.fromEntries(r.nodes.map((n) => [n.id, n.amount]));

  it("removes exactly the node's recurring-due and estimate from expenses, hub and saved", () => {
    const r = withActualsOnly(base, new Set(["expense:home"]));

    expect(r.expenses).toBe("2010.52");
    expect(r.saved).toBe("989.48");
    expect(r.deficit).toBe("0.00");
    expect(amounts(r)).toEqual({ "income.salary": "3000.00", hub: "3000.00", home: "1890.12", leisure: "120.40", saved: "989.48" });
    expect(r.links).toContainEqual({ source: "hub", target: "home", amount: "1890.12" });
  });

  it("turns saved into a deficit when cutting income flips the sign, and back", () => {
    const tight = { ...base, expenses: "2799.36", saved: "200.64", nodes: base.nodes.map((n) => (n.id === "home" ? { ...n, amount: "2678.96" } : n.id === "saved" ? { ...n, amount: "200.64" } : n)) };

    const r = withActualsOnly(tight, new Set(["income:income.salary"]));

    expect(r.income).toBe("2500.00");
    expect(r.saved).toBe("0.00");
    expect(r.deficit).toBe("299.36");
    expect(amounts(r)).toEqual({ "income.salary": "2500.00", deficit: "299.36", hub: "2799.36", home: "2678.96", leisure: "120.40" });
    expect(r.nodes.map((n) => n.side)).toEqual(["income", "deficit", "hub", "expense", "expense"]);
    expect(r.links).toContainEqual({ source: "deficit", target: "hub", amount: "299.36" });
  });

  it("round-trips to the exact original figures once nothing is disabled", () => {
    expect(withActualsOnly(base, new Set())).toBe(base);
    const restored = withActualsOnly(base, presentDisabledKeys(base, new Set<string>()));
    expect(restored).toEqual(base);
  });

  it("keeps a zero-actuals node at zero instead of dropping it", () => {
    const r = withActualsOnly(base, new Set(["expense:leisure"]));

    expect(amounts(r).leisure).toBe("0.00");
    expect(r.expenses).toBe("2278.96");
    expect(r.saved).toBe("721.04");
  });

  it("draws a refund-negative actual at zero but keeps the exact actual in the totals", () => {
    const refunded = {
      ...base,
      components: base.components.map((c) => (c.id === "leisure" ? { ...c, actual: "-15.00", projected: "105.40" } : c)),
      expenses: "2384.36",
      saved: "615.64",
    };

    const r = withActualsOnly(refunded, new Set(["expense:leisure"]));

    expect(amounts(r).leisure).toBe("0.00");
    expect(r.expenses).toBe("2263.96");
    expect(r.saved).toBe("736.04");
  });

  it("ignores disabled keys a refetch no longer returns", () => {
    expect(presentDisabledKeys(base, new Set(["expense:home", "expense:gone"]))).toEqual(new Set(["expense:home"]));
  });
});

describe("modeSettingsOrder", () => {
  const drawn = (id: string, side: SankeyNodeDatum["side"], groupCode: string | null): SankeyNodeDatum => ({
    ...datum(id, side),
    groupCode,
  });
  const groups = ["home", "transport", "groceries", "shopping", "outings", "financial", "health", "personal"];

  it("lists the groups the diagram draws in its order, then the rest in catalog order", () => {
    const nodes = [
      drawn("income.salary", "income", null),
      drawn("hub", "hub", null),
      drawn("outings", "expense", "outings"),
      drawn("home", "expense", "home"),
      drawn("__others_expense__", "others", null),
      drawn("saved", "saved", null),
    ];

    expect(modeSettingsOrder(nodes, groups)).toEqual(
      ["outings", "home", "transport", "groceries", "shopping", "financial", "health", "personal"],
    );
  });

  it("places a group once, at its first drawn category, in the category breakdown", () => {
    const nodes = [
      drawn("groceries.groceries", "expense", "groceries"),
      drawn("home.rent", "expense", "home"),
      drawn("groceries.other", "expense", "groceries"),
      drawn("other.thing", "expense", "ungrouped"),
    ];

    expect(modeSettingsOrder(nodes, groups).slice(0, 3)).toEqual(["groceries", "home", "transport"]);
    expect(modeSettingsOrder(nodes, groups)).not.toContain("ungrouped");
  });
});
