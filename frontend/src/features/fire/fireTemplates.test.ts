import { describe, expect, it } from "vitest";
import i18n from "@/i18n";
import type { FireCashFlowEvent } from "@/api/fire";
import { cashFlowEventProblems, eventsSummary, templateEvents } from "./fireTemplates";

describe("life-event templates", () => {
  it("the mortgage ending is a spending decrease from that year", () => {
    expect(templateEvents({ template: "mortgage_end", year: 2038, monthly: "650", label: "Mortgage ends" })).toEqual([
      { year: 2038, kind: "spending_delta", amount: "-650.00", label: "Mortgage ends", template: "mortgage_end" },
    ]);
  });

  it("the state pension is a decrease in the spending to cover from that year", () => {
    expect(templateEvents({ template: "state_pension", year: 2052, monthly: "1200.5", label: "State pension" })).toEqual([
      { year: 2052, kind: "spending_delta", amount: "-1200.50", label: "State pension", template: "state_pension" },
    ]);
  });

  it("part-time income lowers the spending to cover between two years and restores it after", () => {
    expect(
      templateEvents({ template: "part_time_income", year: 2040, untilYear: 2045, monthly: "800", label: "Part-time" }),
    ).toEqual([
      { year: 2040, kind: "spending_delta", amount: "-800.00", label: "Part-time", template: "part_time_income" },
      { year: 2045, kind: "spending_delta", amount: "800.00", label: "Part-time", template: "part_time_income" },
    ]);
  });

  it("a big expense takes money out and a one-off inflow puts it in", () => {
    expect(templateEvents({ template: "big_expense", year: 2028, amount: "25000", label: "New car" })).toEqual([
      { year: 2028, kind: "lump_sum", amount: "-25000.00", label: "New car", template: "big_expense" },
    ]);
    expect(templateEvents({ template: "windfall", year: 2030, amount: "40000", label: "Inheritance" })).toEqual([
      { year: 2030, kind: "lump_sum", amount: "40000.00", label: "Inheritance", template: "windfall" },
    ]);
  });

  it("the events summary reads in plain words", async () => {
    await i18n.changeLanguage("en");
    const events: FireCashFlowEvent[] = [
      ...templateEvents({ template: "mortgage_end", year: 2038, monthly: "650", label: "Mortgage" }),
      ...templateEvents({ template: "state_pension", year: 2052, monthly: "1200", label: "Pension" }),
    ];
    expect(eventsSummary(events, i18n.t)).toBe("Mortgage until 2038 · pension from 2052");

    const withManual = [...events, { year: 2031, kind: "contribution_delta" as const, amount: "100.00", label: "Raise" }];
    expect(eventsSummary(withManual, i18n.t)).toBe("Mortgage until 2038 · pension from 2052 · 1 other event");
    expect(eventsSummary([], i18n.t)).toBe("none");
  });

  it("flags a one-off amount the projection would not simulate", () => {
    const events: FireCashFlowEvent[] = [
      { year: 2026, kind: "lump_sum", amount: "10000.00", label: "Bonus" },
      { year: 2026, kind: "spending_delta", amount: "-100.00", label: "Cheaper" },
      { year: 2027, kind: "lump_sum", amount: "10000.00", label: "Next year" },
      { year: 2060, kind: "lump_sum", amount: "10000.00", label: "Far" },
    ];
    expect(cashFlowEventProblems(events, 2026, 2046)).toEqual([
      { index: 0, problem: "not_simulated", firstYear: 2027 },
      { index: 3, problem: "after_horizon", firstYear: 2027 },
    ]);
  });
});
