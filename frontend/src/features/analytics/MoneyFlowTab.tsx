import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useMoneyFlow, useYearsAvailable } from "@/api/analytics";
import { Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";
import { isoToday, monthName } from "@/lib/dates";
import { MoneyFlowSankeyChart, MoneyFlowTotals, useMoneyFlowSankey } from "./MoneyFlowChart";
import { MoneyFlowProjectionPanel } from "./MoneyFlowProjectionPanel";
import { resolveMoneyFlowRange, type MoneyFlowScope } from "./moneyflow";
import { isCurrentMonthScope } from "./moneyflowProjection";

type Level = "group" | "category";

export function MoneyFlowTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const now = new Date();
  const { data: years } = useYearsAvailable(household.householdId);
  const [scope, setScope] = useState<MoneyFlowScope>("month");
  const [year, setYear] = useState<number>(now.getFullYear());
  const [month, setMonth] = useState<number>(now.getMonth() + 1);
  const [customFrom, setCustomFrom] = useState<string>("");
  const [customTo, setCustomTo] = useState<string>(isoToday());
  const [level, setLevel] = useState<Level>("group");

  const range = resolveMoneyFlowRange({ scope, year, month, customFrom, customTo }, now);
  const { data, isLoading } = useMoneyFlow(
    household.householdId,
    range?.from ?? "",
    range?.to ?? "",
    level,
    range != null,
  );

  const yearOptions = useMemo(() => {
    const fromServer = years?.years ?? [];
    if (fromServer.length === 0) return [now.getFullYear()];
    return fromServer;
  }, [years, now]);

  const sankey = useMoneyFlowSankey(household.householdId, data);

  const currency = household.currency;
  const hasFlows = sankey != null && sankey.nodes.length > 0;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.money_flow")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.money_flow_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
            <div>
              <Label>{t("analytics.scope")}</Label>
              <Select value={scope} onChange={(e) => setScope(e.target.value as MoneyFlowScope)}>
                <option value="month">{t("analytics.scope_month")}</option>
                <option value="trailing12">{t("analytics.scope_trailing12")}</option>
                <option value="ytd">{t("analytics.scope_ytd")}</option>
                <option value="year">{t("analytics.scope_year")}</option>
                <option value="custom">{t("analytics.scope_custom")}</option>
              </Select>
            </div>
            {(scope === "month" || scope === "year") && (
              <div>
                <Label>{t("analytics.select_year")}</Label>
                <Select value={year} onChange={(e) => setYear(Number(e.target.value))}>
                  {yearOptions.map((y) => (
                    <option key={y} value={y}>{y}</option>
                  ))}
                </Select>
              </div>
            )}
            {scope === "month" && (
              <div>
                <Label>{t("analytics.select_month")}</Label>
                <Select value={month} onChange={(e) => setMonth(Number(e.target.value))}>
                  {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
                    <option key={m} value={m}>{monthName(m, i18n.language, "long")}</option>
                  ))}
                </Select>
              </div>
            )}
            {scope === "custom" && (
              <>
                <div>
                  <Label>{t("tx.filter_from")}</Label>
                  <Input type="date" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} />
                </div>
                <div>
                  <Label>{t("tx.filter_to")}</Label>
                  <Input type="date" value={customTo} onChange={(e) => setCustomTo(e.target.value)} />
                </div>
              </>
            )}
            <div>
              <Label>{t("analytics.money_flow_level")}</Label>
              <Select value={level} onChange={(e) => setLevel(e.target.value as Level)}>
                <option value="group">{t("analytics.money_flow_by_group")}</option>
                <option value="category">{t("analytics.money_flow_by_category")}</option>
              </Select>
            </div>
          </div>

          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && !hasFlows && (
            <p className="text-gray-500 dark:text-gray-400">{t("analytics.money_flow_empty")}</p>
          )}

          {!isLoading && data && sankey && hasFlows && (
            <>
              <MoneyFlowSankeyChart sankey={sankey} currency={currency} />
              <MoneyFlowTotals
                totals={data}
                currency={currency}
                labelOf={(field) =>
                  t({ income: "analytics.income", expenses: "analytics.expenses", saved: "analytics.saved", deficit: "analytics.money_flow_deficit" }[field])
                }
              />
            </>
          )}
        </CardBody>
      </Card>

      {isCurrentMonthScope({ scope, year, month }, now) && (
        <MoneyFlowProjectionPanel householdId={household.householdId} currency={currency} />
      )}
    </div>
  );
}
