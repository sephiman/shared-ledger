import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMoneyFlowProjection, type MoneyFlowProjectionResponse } from "@/api/analytics";
import { Badge, Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { MoneyFlowSankeyChart, MoneyFlowTotals, useMoneyFlowSankey } from "./MoneyFlowChart";
import { FORECAST_WINDOWS, parseForecastWindow, useForecastWindow } from "./forecastWindow";
import type { SankeyNodeDatum } from "./moneyflow";
import { basisMonths, compactMonthSpan, componentsLookup, hasEstimatedPart, monthList } from "./moneyflowProjection";

/** Per browser, like the theme: the panel doubles the tab's height, so whoever folds it keeps it folded. */
const COLLAPSED_KEY = "sl.analytics.moneyFlowProjectionCollapsed";

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === "1";
  } catch {
    return false;
  }
}

type Translate = ReturnType<typeof useTranslation>["t"];

function basisHeadline(r: MoneyFlowProjectionResponse, t: Translate, locale: string): string {
  const span = compactMonthSpan(basisMonths(r), locale);
  if (r.monthsAvailable === 0) return t("analytics.money_flow_projection_basis_none");
  if (r.monthsAvailable < r.windowMonths) {
    return t("analytics.money_flow_projection_basis_partial", { available: r.monthsAvailable, window: r.windowMonths, span });
  }
  return t("analytics.money_flow_projection_basis", { window: r.windowMonths, span });
}

function windowAssumption(r: MoneyFlowProjectionResponse, t: Translate, locale: string): string {
  const months = monthList(basisMonths(r), locale);
  if (r.monthsAvailable === 0) return t("analytics.money_flow_projection_window_none", { window: r.windowMonths });
  if (r.monthsAvailable < r.windowMonths) {
    return t("analytics.money_flow_projection_window_partial", { window: r.windowMonths, months, available: r.monthsAvailable });
  }
  return t("analytics.money_flow_projection_window", { window: r.windowMonths, months });
}

export function MoneyFlowProjectionPanel({
  householdId,
  currency,
  level,
}: {
  householdId: string;
  currency: string;
  level: "group" | "category";
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [window, setWindow] = useForecastWindow();
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const { data, isLoading } = useMoneyFlowProjection(householdId, level, window);
  const sankey = useMoneyFlowSankey(householdId, data, true);

  useEffect(() => {
    try {
      localStorage.setItem(COLLAPSED_KEY, collapsed ? "1" : "0");
    } catch {
      // A browser that refuses storage still folds the panel; it just won't remember it.
    }
  }, [collapsed]);

  const detailLine = useMemo(() => {
    if (!data) return undefined;
    const lookup = componentsLookup(data.components);
    const span = compactMonthSpan(basisMonths(data), locale);
    const money = (v: string) => formatMoney(v, currency, locale);
    return (node: SankeyNodeDatum): string | null => {
      const c = lookup(node);
      if (!c) return null;
      if (!hasEstimatedPart(c)) return t("analytics.money_flow_projection_settled", { actual: money(c.actual) });
      const parts = {
        actual: money(c.actual),
        recurring: money(c.recurringDue),
        discretionary: money(c.discretionaryEstimate),
        projected: money(c.projected),
        span,
      };
      return data.monthsAvailable === 0
        ? t("analytics.money_flow_projection_breakdown_no_history", parts)
        : t("analytics.money_flow_projection_breakdown", parts);
    };
  }, [data, currency, locale, t]);

  const hasFlows = sankey != null && sankey.nodes.length > 0;
  const projectedLabel = (field: "income" | "expenses" | "saved" | "deficit") =>
    t(
      {
        income: "analytics.projected_income",
        expenses: "analytics.projected_expenses",
        saved: "analytics.saved_projected",
        deficit: "analytics.deficit_projected",
      }[field],
    );

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <button
              type="button"
              className="flex items-center gap-2 text-left font-medium"
              aria-expanded={!collapsed}
              aria-controls="money-flow-projection-body"
              title={t("analytics.money_flow_projection_toggle")}
              onClick={() => setCollapsed((c) => !c)}
            >
              <span aria-hidden className="inline-block w-3 text-gray-500 dark:text-gray-400">{collapsed ? "▸" : "▾"}</span>
              <span>{t("analytics.money_flow_projection")}</span>
              <Badge tone="sky">{t("analytics.money_flow_projection_badge")}</Badge>
            </button>
            {data && (
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{basisHeadline(data, t, locale)}</p>
            )}
          </div>
          <div>
            <Label className="text-xs">{t("analytics.window")}</Label>
            <Select
              value={window}
              onChange={(e) => setWindow(parseForecastWindow(e.target.value))}
              title={t("analytics.window_help")}
              aria-label={t("analytics.window")}
            >
              {FORECAST_WINDOWS.map((w) => <option key={w} value={w}>{w}</option>)}
            </Select>
          </div>
        </div>
      </CardHeader>
      {!collapsed && (
        <CardBody id="money-flow-projection-body" className="space-y-4">
          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && !hasFlows && (
            <p className="text-gray-500 dark:text-gray-400">{t("analytics.money_flow_projection_empty")}</p>
          )}

          {!isLoading && data && sankey && hasFlows && (
            <>
              <MoneyFlowSankeyChart sankey={sankey} currency={currency} projected detailLine={detailLine} />
              <MoneyFlowTotals totals={data} currency={currency} labelOf={projectedLabel} />
            </>
          )}

          {data && (
            <ul className="space-y-0.5 border-t border-border pt-3 text-xs text-gray-500 dark:text-gray-400">
              <li>
                {t("analytics.money_flow_projection_day", {
                  day: data.dayOfMonth,
                  days: data.daysInMonth,
                  percent: formatNumber((data.dayOfMonth / data.daysInMonth) * 100, locale, 0),
                })}
              </li>
              <li>{windowAssumption(data, t, locale)}</li>
              <li>{t("analytics.money_flow_projection_recurring")}</li>
            </ul>
          )}
        </CardBody>
      )}
    </Card>
  );
}
