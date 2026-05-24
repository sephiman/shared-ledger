import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAllocation, useYearsAvailable } from "@/api/analytics";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { formatMoney, formatNumber } from "@/lib/money";
import { monthName } from "@/lib/dates";

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444", "#14b8a6", "#eab308", "#ec4899"];
const SAVED_COLOR = "#16a34a";

type ScopeKind = "month" | "year";

export function AllocationTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const now = new Date();
  const { data: years } = useYearsAvailable(household.householdId);
  const [scope, setScope] = useState<ScopeKind>("month");
  const [year, setYear] = useState<number>(now.getFullYear());
  const [month, setMonth] = useState<number>(now.getMonth() + 1);

  const { data, isLoading } = useAllocation(
    household.householdId,
    year,
    scope === "month" ? month : null,
  );

  const yearOptions = useMemo(() => {
    const fromServer = years?.years ?? [];
    if (fromServer.length === 0) return [now.getFullYear()];
    return fromServer;
  }, [years, now]);

  const chartData = useMemo(() => {
    if (!data) return [];
    const slices = data.slices.map((s) => ({
      key: s.groupCode,
      name: t(`category_group.${s.groupCode}`),
      amount: Number(s.amount),
      percent: s.percentOfIncome,
    }));
    const savedNum = Number(data.saved);
    if (savedNum > 0) {
      slices.push({
        key: "__saved__",
        name: t("analytics.saved"),
        amount: savedNum,
        percent: Number(data.income) > 0 ? (savedNum / Number(data.income)) * 100 : 0,
      });
    }
    return slices;
  }, [data, t]);

  const incomeZero = data ? Number(data.income) === 0 : false;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.allocation")}</p>
          <p className="mt-1 text-sm text-gray-500">{t("analytics.allocation_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <div>
              <Label>{t("analytics.scope")}</Label>
              <Select value={scope} onChange={(e) => setScope(e.target.value as ScopeKind)}>
                <option value="month">{t("analytics.scope_month")}</option>
                <option value="year">{t("analytics.scope_year")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("analytics.select_year")}</Label>
              <Select value={year} onChange={(e) => setYear(Number(e.target.value))}>
                {yearOptions.map((y) => (
                  <option key={y} value={y}>{y}</option>
                ))}
              </Select>
            </div>
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

          {isLoading && <p className="text-gray-500">{t("common.loading")}</p>}

          {!isLoading && data && chartData.length === 0 && (
            <p className="text-gray-500">{t("common.empty")}</p>
          )}

          {!isLoading && data && chartData.length > 0 && (
            <>
              {incomeZero && (
                <p className="text-sm text-gray-500">{t("analytics.allocation_no_income")}</p>
              )}
              <div className="h-72">
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
                    >
                      {chartData.map((entry, i) => (
                        <Cell
                          key={entry.key}
                          fill={entry.key === "__saved__" ? SAVED_COLOR : PALETTE[i % PALETTE.length]}
                        />
                      ))}
                    </Pie>
                    <Tooltip
                      formatter={(value: unknown, name: unknown) => [
                        formatMoney(Number(value), household.currency, i18n.language),
                        String(name ?? ""),
                      ]}
                    />
                    <Legend />
                  </PieChart>
                </ResponsiveContainer>
              </div>

              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-left text-gray-500">
                    <tr>
                      <th className="py-2">{t("common.category")}</th>
                      <th className="text-right">{t("common.amount")}</th>
                      <th className="text-right">{t("analytics.percent_of_income")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {chartData.map((row) => (
                      <tr key={row.key} className="border-t border-border">
                        <td className="py-2">{row.name}</td>
                        <td className="text-right">{formatMoney(row.amount, household.currency, i18n.language)}</td>
                        <td className="text-right">{formatNumber(row.percent, i18n.language, 1)}%</td>
                      </tr>
                    ))}
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
