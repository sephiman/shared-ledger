import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useTrailing12 } from "@/api/analytics";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { monthName } from "@/lib/dates";
import { formatMoney, formatNumber } from "@/lib/money";
import { ChartTooltip } from "@/components/charts/ChartTooltip";

export function TrailingTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data } = useTrailing12(household.householdId);
  const points = data?.points ?? [];
  const chartData = points.map((p) => ({
    period: `${monthName(p.month, i18n.language, "short")} ${String(p.year).slice(2)}`,
    income: Number(p.income),
    expenses: Number(p.expenses),
    savings: p.savingsRate,
  }));

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.income")} / {t("analytics.expenses")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.trailing_description")}</p>
        </CardHeader>
        <CardBody className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData}>
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
            </BarChart>
          </ResponsiveContainer>
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
