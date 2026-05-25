import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useYearByYear } from "@/api/analytics";
import { Card, CardBody, CardHeader, Chip } from "@/components/ui/primitives";
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Formatter, ValueType, NameType } from "recharts/types/component/DefaultTooltipContent";
import { monthName } from "@/lib/dates";

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444"];

type Metric = "expenses" | "savingsRate";

export function YearByYearTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const currentYear = new Date().getFullYear();
  const [years, setYears] = useState<number[]>([currentYear - 1, currentYear]);
  const [metric, setMetric] = useState<Metric>("expenses");
  const { data } = useYearByYear(household.householdId, years);

  function toggle(y: number) {
    setYears((prev) => (prev.includes(y) ? prev.filter((x) => x !== y) : [...prev, y].sort()));
  }

  const chartData = useMemo(() => {
    if (!data) return [];
    const months = Array.from({ length: 12 }, (_, i) => i + 1);
    return months.map((m) => {
      const row: Record<string, number | string> = { month: monthName(m, i18n.language, "short") };
      for (const s of data.series) {
        if (metric === "expenses") {
          row[`v_${s.year}`] = Number(s.expensesPerMonth[m - 1] ?? 0);
        } else {
          row[`v_${s.year}`] = Number(s.savingsRatePerMonth?.[m - 1] ?? 0);
        }
      }
      return row;
    });
  }, [data, i18n.language, metric]);

  const yAxisFormatter = metric === "savingsRate" ? (v: number) => `${v.toFixed(0)}%` : undefined;
  const tooltipFormatter: Formatter<ValueType, NameType> | undefined = metric === "savingsRate"
    ? (value, name) => [`${Number(value ?? 0).toFixed(1)}%`, name ?? ""]
    : undefined;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-medium">{t("analytics.yby")}</p>
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.yby_description")}</p>
            </div>
            <div className="flex gap-2">
              <Chip active={metric === "expenses"} onClick={() => setMetric("expenses")}>
                {t("analytics.expenses")}
              </Chip>
              <Chip active={metric === "savingsRate"} onClick={() => setMetric("savingsRate")}>
                {t("analytics.savings_rate")}
              </Chip>
            </div>
          </div>
        </CardHeader>
        <CardBody>
          <div className="mb-3 flex flex-wrap gap-2">
            {Array.from({ length: 7 }).map((_, i) => {
              const y = currentYear - 3 + i;
              return (
                <Chip key={y} active={years.includes(y)} onClick={() => toggle(y)}>
                  {y}
                </Chip>
              );
            })}
          </div>
          <div className="h-72">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="month" />
                <YAxis tickFormatter={yAxisFormatter} />
                <Tooltip formatter={tooltipFormatter} />
                <Legend />
                {years.map((y, idx) => (
                  <Line key={y} type="monotone" dataKey={`v_${y}`} name={String(y)} stroke={PALETTE[idx % PALETTE.length]} dot={false} />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
