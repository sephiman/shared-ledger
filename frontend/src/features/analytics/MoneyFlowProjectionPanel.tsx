import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useMoneyFlowProjection, type MoneyFlowProjectionResponse } from "@/api/analytics";
import { Badge, Button, Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { MoneyFlowSankeyChart, MoneyFlowTotals, useMoneyFlowSankey } from "./MoneyFlowChart";
import { FORECAST_WINDOWS, parseForecastWindow, useForecastWindow } from "./forecastWindow";
import { nodeKey, type SankeyNodeDatum } from "./moneyflow";
import {
  basisMonths,
  compactMonthSpan,
  componentsLookup,
  estimatedRemainder,
  hasEstimatedPart,
  monthList,
  presentDisabledKeys,
  withActualsOnly,
} from "./moneyflowProjection";

type Level = "group" | "category";

/** Nodes switched to actuals-only, valid only for the breakdown and window they were clicked under. */
interface ActualsOnlyNodes {
  scope: string;
  keys: ReadonlySet<string>;
}

const NONE: ReadonlySet<string> = new Set();

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

export function MoneyFlowProjectionPanel({ householdId, currency }: { householdId: string; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [window, setWindow] = useForecastWindow();
  // Exploration state, deliberately not remembered: every visit starts from the group view with nothing switched off.
  const [level, setLevel] = useState<Level>("group");
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const { data: response, isLoading } = useMoneyFlowProjection(householdId, level, window);

  // Cleared on any scope change, not in the selectors' handlers: the window is shared with the Forecast and may
  // change elsewhere. Dropped outright so that going back to the previous scope doesn't bring old toggles back.
  const scope = `${level}:${window}`;
  const [actualsOnly, setActualsOnly] = useState<ActualsOnlyNodes>({ scope, keys: NONE });
  if (actualsOnly.scope !== scope) setActualsOnly({ scope, keys: NONE });
  const disabledKeys = useMemo(() => {
    if (!response || actualsOnly.scope !== scope) return NONE;
    return presentDisabledKeys(response, actualsOnly.keys);
  }, [response, actualsOnly, scope]);
  const data = useMemo(() => response && withActualsOnly(response, disabledKeys), [response, disabledKeys]);
  const sankey = useMoneyFlowSankey(householdId, data, true, disabledKeys);

  const toggleNode = (node: SankeyNodeDatum) => {
    const key = nodeKey(node);
    const next = new Set(disabledKeys);
    if (!next.delete(key)) next.add(key);
    setActualsOnly({ scope, keys: next });
  };
  const restoreAll = () => setActualsOnly({ scope, keys: NONE });

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
      if (disabledKeys.has(nodeKey(node))) {
        return t("analytics.money_flow_projection_disabled", { excluded: money(estimatedRemainder(c).toFixed(2)) });
      }
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
  }, [data, disabledKeys, currency, locale, t]);

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
          <div className="flex gap-3">
            <div>
              <Label className="text-xs">{t("analytics.money_flow_level")}</Label>
              <Select
                value={level}
                onChange={(e) => setLevel(e.target.value as Level)}
                aria-label={t("analytics.money_flow_projection_level")}
              >
                <option value="group">{t("analytics.money_flow_by_group")}</option>
                <option value="category">{t("analytics.money_flow_by_category")}</option>
              </Select>
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
              <MoneyFlowSankeyChart
                sankey={sankey}
                currency={currency}
                projected
                detailLine={detailLine}
                toggle={{
                  disabledKeys,
                  onToggle: toggleNode,
                  labelOf: (node) => t("analytics.money_flow_projection_node_toggle", { name: node.name }),
                }}
              />
              <MoneyFlowTotals totals={data} currency={currency} labelOf={projectedLabel}>
                {disabledKeys.size > 0 && (
                  <span className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                    <Badge tone="amber">
                      {t(
                        level === "group"
                          ? "analytics.money_flow_projection_disabled_groups"
                          : "analytics.money_flow_projection_disabled_categories",
                        { count: disabledKeys.size },
                      )}
                    </Badge>
                    <Button variant="ghost" className="px-2 py-0.5 text-xs" onClick={restoreAll}>
                      {t("analytics.money_flow_projection_restore_all")}
                    </Button>
                  </span>
                )}
              </MoneyFlowTotals>
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
