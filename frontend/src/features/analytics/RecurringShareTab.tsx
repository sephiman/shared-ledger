import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useRecurringShare, useYearsAvailable, type RecurringShareParams } from "@/api/analytics";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { formatMoney, formatNumber } from "@/lib/money";
import { monthName } from "@/lib/dates";
import { ChartTooltip } from "@/components/charts/ChartTooltip";

type ScopeKey = "month" | "trailing12" | "ytd" | "year";

const RECURRING_COLOR = "#0ea5e9";
const DISCRETIONARY_COLOR = "#f97316";

export function RecurringShareTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const now = new Date();
  const { data: years } = useYearsAvailable(household.householdId);
  const [scope, setScope] = useState<ScopeKey>("month");
  const [year, setYear] = useState<number>(now.getFullYear());
  const [month, setMonth] = useState<number>(now.getMonth() + 1);

  const params = useMemo<RecurringShareParams>(() => {
    switch (scope) {
      case "month": return { scope, year, month };
      case "year": return { scope, year };
      case "trailing12":
      case "ytd":
      default: return { scope };
    }
  }, [scope, year, month]);

  const { data, isLoading } = useRecurringShare(household.householdId, params);

  const yearOptions = useMemo(() => {
    const fromServer = years?.years ?? [];
    if (fromServer.length === 0) return [now.getFullYear()];
    return fromServer;
  }, [years, now]);

  const chartData = useMemo(() => {
    if (!data) return [];
    return [
      { key: "recurring", name: t("analytics.recurring"), amount: Number(data.recurring), fill: RECURRING_COLOR },
      { key: "discretionary", name: t("analytics.discretionary"), amount: Number(data.discretionary), fill: DISCRETIONARY_COLOR },
    ];
  }, [data, t]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.recurring_share")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.recurring_share_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <div>
              <Label>{t("analytics.scope")}</Label>
              <Select value={scope} onChange={(e) => setScope(e.target.value as ScopeKey)}>
                <option value="month">{t("analytics.scope_month")}</option>
                <option value="trailing12">{t("analytics.scope_trailing12")}</option>
                <option value="ytd">{t("analytics.scope_ytd")}</option>
                <option value="year">{t("analytics.scope_year")}</option>
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
          </div>

          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && Number(data.total) === 0 && (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          )}

          {!isLoading && data && Number(data.total) > 0 && (
            <>
              <p className="text-sm">
                {t("analytics.share_summary", {
                  recurring: formatNumber(data.recurringShare, i18n.language, 0),
                  discretionary: formatNumber(data.discretionaryShare, i18n.language, 0),
                })}
              </p>
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={chartData}
                      dataKey="amount"
                      nameKey="name"
                      cx="50%"
                      cy="50%"
                      innerRadius="55%"
                      outerRadius="85%"
                      paddingAngle={1}
                    />
                    <Tooltip
                      content={(props) => (
                        <ChartTooltip
                          {...props}
                          formatValue={(v) => formatMoney(Number(v), household.currency, i18n.language)}
                        />
                      )}
                    />
                    <Legend />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-left text-gray-500 dark:text-gray-400">
                    <tr>
                      <th className="py-2">{t("common.category")}</th>
                      <th className="text-right">{t("common.amount")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr className="border-t border-border">
                      <td className="py-2">{t("analytics.recurring")}</td>
                      <td className="text-right">{formatMoney(data.recurring, household.currency, i18n.language)}</td>
                    </tr>
                    <tr className="border-t border-border">
                      <td className="py-2">{t("analytics.discretionary")}</td>
                      <td className="text-right">{formatMoney(data.discretionary, household.currency, i18n.language)}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
