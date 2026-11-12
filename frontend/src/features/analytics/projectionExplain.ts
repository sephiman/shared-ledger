import Decimal from "decimal.js";
import type { MoneyFlowProjectionResponse, ProjectedFlowComponents } from "@/api/analytics";
import type { ProjectionExplain } from "@/api/questions";
import { formatMoney, toDecimal } from "@/lib/money";
import { estimatedRemainder } from "./moneyflowProjection";

export interface ProjectionExplanation {
  title: string;
  summary: string;
  items: string[];
}

type Translate = (key: string, opts?: Record<string, unknown>) => string;
type LabelOf = (side: "income" | "expense", id: string) => string;

export function buildProjectionExplanation(
  data: MoneyFlowProjectionResponse,
  explain: ProjectionExplain,
  currency: string,
  locale: string,
  labelOf: LabelOf,
  t: Translate,
): ProjectionExplanation | null {
  const money = (v: string | number | Decimal) => formatMoney(typeof v === "string" ? v : v.toString(), currency, locale);
  const label = (c: ProjectedFlowComponents) => labelOf(c.side, c.id);

  switch (explain.kind) {
    case "node": {
      const target = explain.scope;
      const comp = target ? data.components.find((c) => c.id === target.code) : undefined;
      if (!comp) {
        return {
          title: t("analytics.projection_ask_explain_node_title", { name: target?.code ?? "" }),
          summary: t("analytics.projection_ask_explain_node_missing"),
          items: [],
        };
      }
      return {
        title: t("analytics.projection_ask_explain_node_title", { name: label(comp) }),
        summary: t("analytics.projection_ask_explain_node_summary", {
          projected: money(comp.projected),
          actual: money(comp.actual),
          recurring: money(comp.recurringDue),
          discretionary: money(comp.discretionaryEstimate),
        }),
        items: comp.projectionMode
          ? [t("analytics.projection_ask_explain_mode", { mode: comp.projectionMode })]
          : [],
      };
    }

    case "recurring_due": {
      const due = data.components
        .filter((c) => toDecimal(c.recurringDue).gt(0))
        .sort((a, b) => toDecimal(b.recurringDue).cmp(toDecimal(a.recurringDue)));
      const total = due.reduce((acc, c) => acc.plus(c.recurringDue), toDecimal(0));
      return {
        title: t("analytics.projection_ask_explain_recurring_title"),
        summary: due.length
          ? t("analytics.projection_ask_explain_recurring_summary", { count: due.length, total: money(total) })
          : t("analytics.projection_ask_explain_recurring_none"),
        items: due.slice(0, 6).map((c) => `${label(c)}: ${money(c.recurringDue)}`),
      };
    }

    case "top_expenses": {
      const top = data.components
        .filter((c) => c.side === "expense" && toDecimal(c.projected).gt(0))
        .sort((a, b) => toDecimal(b.projected).cmp(toDecimal(a.projected)))
        .slice(0, 5);
      return {
        title: t("analytics.projection_ask_explain_top_title"),
        summary: t("analytics.projection_ask_explain_top_summary", { total: money(data.expenses) }),
        items: top.map((c) => `${label(c)}: ${money(c.projected)}`),
      };
    }

    case "deficit_drivers": {
      const expenses = data.components
        .filter((c) => c.side === "expense" && toDecimal(c.projected).gt(0))
        .sort((a, b) => toDecimal(b.projected).cmp(toDecimal(a.projected)));
      const hasDeficit = toDecimal(data.deficit).gt(0);
      return {
        title: t("analytics.projection_ask_explain_deficit_title"),
        summary: hasDeficit
          ? t("analytics.projection_ask_explain_deficit_summary", {
              deficit: money(data.deficit),
              income: money(data.income),
              expenses: money(data.expenses),
            })
          : t("analytics.projection_ask_explain_surplus_summary", {
              saved: money(data.saved),
              income: money(data.income),
              expenses: money(data.expenses),
            }),
        items: expenses.slice(0, 4).map((c) =>
          t("analytics.projection_ask_explain_driver_item", {
            name: label(c),
            projected: money(c.projected),
            remaining: money(estimatedRemainder(c)),
          }),
        ),
      };
    }

    case "outlook": {
      const remainingExpenses = data.components
        .filter((c) => c.side === "expense")
        .reduce((acc, c) => acc.plus(estimatedRemainder(c)), toDecimal(0));
      const hasDeficit = toDecimal(data.deficit).gt(0);
      return {
        title: t("analytics.projection_ask_explain_outlook_title"),
        summary: hasDeficit
          ? t("analytics.projection_ask_explain_outlook_deficit", {
              income: money(data.income),
              expenses: money(data.expenses),
              deficit: money(data.deficit),
            })
          : t("analytics.projection_ask_explain_outlook_saved", {
              income: money(data.income),
              expenses: money(data.expenses),
              saved: money(data.saved),
            }),
        items: [
          t("analytics.projection_ask_explain_outlook_remaining", {
            remaining: money(remainingExpenses),
            daysRemaining: data.daysRemaining,
          }),
        ],
      };
    }
  }
}
