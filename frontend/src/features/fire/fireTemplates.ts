import type { TFunction } from "i18next";
import type { FireCashFlowEvent } from "@/api/fire";
import { toDecimal } from "@/lib/money";

export type FireEventTemplate = "mortgage_end" | "state_pension" | "part_time_income" | "big_expense" | "windfall";

export const FIRE_EVENT_TEMPLATES: readonly FireEventTemplate[] = [
  "mortgage_end",
  "state_pension",
  "part_time_income",
  "big_expense",
  "windfall",
];

/** What a template asks for, in plain language; amounts are always in today's money. */
export type TemplateAnswers =
  | { template: "mortgage_end" | "state_pension"; year: number; monthly: string; label: string }
  | { template: "part_time_income"; year: number; untilYear: number; monthly: string; label: string }
  | { template: "big_expense" | "windfall"; year: number; amount: string; label: string };

function cents(value: string): string {
  return toDecimal(value).toFixed(2);
}

function negated(value: string): string {
  return toDecimal(value).negated().toFixed(2);
}

/** The ordinary cash-flow events a template stands for — nothing the engine does not already model. */
export function templateEvents(a: TemplateAnswers): FireCashFlowEvent[] {
  const base = { label: a.label, template: a.template };
  switch (a.template) {
    case "mortgage_end":
    case "state_pension":
      return [{ ...base, year: a.year, kind: "spending_delta", amount: negated(a.monthly) }];
    case "part_time_income":
      return [
        { ...base, year: a.year, kind: "spending_delta", amount: negated(a.monthly) },
        { ...base, year: a.untilYear, kind: "spending_delta", amount: cents(a.monthly) },
      ];
    case "big_expense":
      return [{ ...base, year: a.year, kind: "lump_sum", amount: negated(a.amount) }];
    case "windfall":
      return [{ ...base, year: a.year, kind: "lump_sum", amount: cents(a.amount) }];
  }
}

/** "Mortgage until 2038 · pension from 2052 · 1 other event". */
export function eventsSummary(events: FireCashFlowEvent[], t: TFunction): string {
  if (events.length === 0) return t("fire.events_summary_none");
  const parts: string[] = [];
  let others = 0;
  const partTime = events.filter((e) => e.template === "part_time_income");
  for (const e of events) {
    switch (e.template) {
      case "mortgage_end":
        parts.push(t("fire.events_summary_mortgage_end", { year: e.year }));
        break;
      case "state_pension":
        parts.push(t("fire.events_summary_state_pension", { year: e.year }));
        break;
      case "part_time_income":
        // A part-time stretch is two events; it reads once, from the start.
        if (Number(e.amount) < 0) {
          const end = partTime.find((p) => Number(p.amount) > 0 && p.year > e.year && p.label === e.label);
          parts.push(t("fire.events_summary_part_time", { from: e.year, to: end?.year ?? "…" }));
        }
        break;
      case "big_expense":
      case "windfall":
        parts.push(t("fire.events_summary_one_off", { label: e.label || t(`fire.template_${e.template}`), year: e.year }));
        break;
      default:
        others += 1;
    }
  }
  if (others > 0) parts.push(t("fire.events_summary_others", { count: others }));
  const text = parts.join(" · ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export type CashFlowEventProblem = { index: number; problem: "not_simulated" | "after_horizon"; firstYear: number };

/** One-off amounts the projection would not add: dated in or before the snapshot year (never simulated) or after
 *  the horizon (simulated only once the horizon reaches them). */
export function cashFlowEventProblems(events: FireCashFlowEvent[], startYear: number, horizon: number): CashFlowEventProblem[] {
  const firstYear = startYear + 1;
  return events.flatMap((e, index): CashFlowEventProblem[] => {
    if (e.kind !== "lump_sum") return [];
    if (e.year <= startYear) return [{ index, problem: "not_simulated", firstYear }];
    if (e.year > horizon) return [{ index, problem: "after_horizon", firstYear }];
    return [];
  });
}
