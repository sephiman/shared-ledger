import { describe, expect, it } from "vitest";
import type { TrailingPoint } from "@/api/analytics";
import { buildTrailingChartData } from "./trailing";

const point = (over: Partial<TrailingPoint>): TrailingPoint => ({
  year: 2025,
  month: 6,
  income: "0.00",
  expenses: "0.00",
  netSavings: "0.00",
  savingsRate: 0,
  ...over,
});

describe("buildTrailingChartData", () => {
  it("carries the server-computed net savings straight through as a numeric series", () => {
    const rows = buildTrailingChartData(
      [
        point({ month: 5, income: "1000.00", expenses: "1200.00", netSavings: "-200.00", savingsRate: 0 }),
        point({ month: 6, income: "2000.00", expenses: "500.00", netSavings: "1500.00", savingsRate: 75 }),
      ],
      "en",
    );

    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({ income: 1000, expenses: 1200, netSavings: -200, savings: 0 });
    expect(rows[1]).toMatchObject({ income: 2000, expenses: 500, netSavings: 1500, savings: 75 });
    expect(rows[1].period).toBeTruthy();
  });

  it("handles an empty (zero-month) series", () => {
    expect(buildTrailingChartData([], "en")).toEqual([]);
  });
});
