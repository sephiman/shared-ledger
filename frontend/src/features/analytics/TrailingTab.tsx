import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useTrailing12 } from "@/api/analytics";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { Bar, CartesianGrid, ComposedChart, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatMoney, formatNumber } from "@/lib/money";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { buildTrailingChartData } from "./trailing";

export function TrailingTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data } = useTrailing12(household.householdId);
  const points = data?.points ?? [];
  const summary = data?.summary;
  const chartData = buildTrailingChartData(points, i18n.language);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.income")} / {t("analytics.expenses")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.trailing_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="h-72">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="period" />
                <YAxis />
                <Tooltip
                  cursor={{ fill: "rgba(14,165,233,0.08)" }}
                  content={(props) => (
                    <ChartTooltip
                      {...props}
                      formatValue={(v) => formatMoney(Number(v), household.currency, i18n.language)}
                    />
                  )}
                />
                <Legend />
                <Bar dataKey="income" name={t("analytics.income")} fill="#22c55e" />
                <Bar dataKey="expenses" name={t("analytics.expenses")} fill="#ef4444" />
                <Line type="monotone" dataKey="netSavings" name={t("analytics.net_savings")} stroke="#0ea5e9" strokeWidth={2} dot={false} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          {summary && (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <Stat label={t("analytics.avg_income_per_month")} value={formatMoney(summary.avgIncome, household.currency, i18n.language)} />
              <Stat label={t("analytics.avg_expenses_per_month")} value={formatMoney(summary.avgExpenses, household.currency, i18n.language)} />
              <Stat label={t("analytics.avg_savings_per_month")} value={formatMoney(summary.avgNetSavings, household.currency, i18n.language)} />
            </div>
          )}
        </CardBody>
      </Card>
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.savings_rate")}</p>
        </CardHeader>
        <CardBody className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="period" />
              <YAxis tickFormatter={(v) => `${v}%`} />
              <Tooltip
                content={(props) => (
                  <ChartTooltip
                    {...props}
                    formatValue={(v) => `${formatNumber(Number(v), i18n.language, 1)}%`}
                  />
                )}
              />
              <Line type="monotone" dataKey="savings" name={t("analytics.savings_rate")} stroke="#0ea5e9" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </CardBody>
      </Card>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-border p-3">
      <p className="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
    </div>
  );
}
