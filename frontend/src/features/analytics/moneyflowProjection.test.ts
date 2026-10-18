import { describe, expect, it } from "vitest";
import type { MoneyFlowProjectionResponse, ProjectedFlowComponents } from "@/api/analytics";
import { parseForecastWindow } from "./forecastWindow";
import type { SankeyNodeDatum } from "./moneyflow";
import {
  basisMonths,
  compactMonthSpan,
  componentsLookup,
  hasEstimatedPart,
  isCurrentMonthScope,
  monthList,
} from "./moneyflowProjection";

function projection(partial: Partial<MoneyFlowProjectionResponse>): MoneyFlowProjectionResponse {
  return {
    year: 2026,
    month: 10,
    level: "group",
    dayOfMonth: 18,
    daysInMonth: 31,
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
