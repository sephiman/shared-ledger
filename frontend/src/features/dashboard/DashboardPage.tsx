import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useDashboardExtras,
  useMonthDashboard,
  useYearDashboard,
  type DashboardExtras,
} from "@/api/analytics";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

interface DashboardData {
  income: string;
  expenses: string;
  savings: string;
  savingsRate: number;
  byGroup: { groupCode: string; amount: string }[];
}

export function DashboardPage() {
  const household = useActiveHousehold();
  const now = new Date();
  const month = useMonthDashboard(household.householdId, now.getFullYear(), now.getMonth() + 1);
  const year = useYearDashboard(household.householdId, now.getFullYear());
  const extras = useDashboardExtras(household.householdId);
  const { t, i18n } = useTranslation();

  if (month.isLoading || !month.data || year.isLoading || !year.data) {
    return <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>;
  }

  const monthTitle = new Date().toLocaleString(i18n.language, { month: "long", year: "numeric" });
  const yearTitle = String(now.getFullYear());

  return (
    <div className="space-y-8">
      <section className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <SavingsRateTile extras={extras.data} locale={i18n.language} />
        <FixedCostTile
          extras={extras.data}
          currency={household.currency}
          locale={i18n.language}
        />
      </section>

      <DashboardSection
        title={monthTitle}
        data={month.data}
        currency={household.currency}
        locale={i18n.language}
      />
      <DashboardSection
        title={yearTitle}
        data={year.data}
        currency={household.currency}
        locale={i18n.language}
      />
    </div>
  );
}

function SavingsRateTile({
  extras,
  locale,
}: {
  extras: DashboardExtras | undefined;
  locale: string;
}) {
  const { t } = useTranslation();
  if (!extras) {
    return (
      <Card>
        <CardHeader>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("dashboard.savings_rate_tile_title")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
        </CardBody>
      </Card>
    );
  }
  const sparkData = extras.sparkline.map((p) => ({
    label: `${p.year}-${String(p.month).padStart(2, "0")}`,
    rate: p.rate,
  }));
  return (
    <Card>
      <CardHeader>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("dashboard.savings_rate_tile_title")}</p>
      </CardHeader>
      <CardBody>
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-3xl font-semibold">
              {formatNumber(extras.trailing12.rate, locale, 1)}%
            </p>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("dashboard.savings_rate_trailing12")}</p>
          </div>
          <div
            className="h-12 w-32 flex-shrink-0"
            aria-label={t("dashboard.savings_rate_sparkline_aria")}
          >
            {sparkData.length > 1 && (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={sparkData}>
                  <Line
                    type="monotone"
                    dataKey="rate"
                    stroke="#0ea5e9"
                    strokeWidth={1.5}
                    dot={false}
                    isAnimationActive={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
        <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <div>
            <p className="text-gray-500 dark:text-gray-400">{t("dashboard.savings_rate_ytd")}</p>
            <p className="font-medium">{formatNumber(extras.ytd.rate, locale, 1)}%</p>
          </div>
          <div>
            <p className="text-gray-500 dark:text-gray-400">{t("dashboard.savings_rate_month")}</p>
            <p className="font-medium">{formatNumber(extras.currentMonth.rate, locale, 1)}%</p>
          </div>
        </div>
      </CardBody>
    </Card>
  );
}

function FixedCostTile({
  extras,
  currency,
  locale,
}: {
  extras: DashboardExtras | undefined;
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  if (!extras) {
    return (
      <Card>
        <CardHeader>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("dashboard.fixed_cost_tile_title")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
        </CardBody>
      </Card>
    );
  }
  if (extras.monthsAvailable === 0) {
    return (
      <Card>
        <CardHeader>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("dashboard.fixed_cost_tile_title")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-2xl font-semibold text-gray-400">—</p>
          <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("dashboard.fixed_cost_no_data")}</p>
        </CardBody>
      </Card>
    );
  }
  const perDay = formatMoney(extras.fixedRecurring.perDay, currency, locale);
  const perYear = formatMoney(extras.fixedRecurring.perYear, currency, locale);
  return (
    <Card>
      <CardHeader>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("dashboard.fixed_cost_tile_title")}</p>
      </CardHeader>
      <CardBody>
        <p className="text-2xl font-semibold">
          {t("dashboard.fixed_cost_per_day", { amount: perDay })}{" · "}
          {t("dashboard.fixed_cost_per_year", { amount: perYear })}
        </p>
        <p
          className="mt-2 text-xs text-gray-500 dark:text-gray-400"
          title={t("dashboard.fixed_cost_based_on", { count: extras.monthsAvailable })}
        >
          {t("dashboard.fixed_cost_with_discretionary", {
            perDay: formatMoney(extras.fixedAll.perDay, currency, locale),
            perYear: formatMoney(extras.fixedAll.perYear, currency, locale),
          })}
        </p>
      </CardBody>
    </Card>
  );
}

function DashboardSection({
  title,
  data,
  currency,
  locale,
}: {
  title: string;
  data: DashboardData;
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  const groupedData = data.byGroup.map((g) => ({
    name: t(`category_group.${g.groupCode}`),
    amount: Number(g.amount),
  }));

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-medium">{title}</h2>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
        <Card>
          <CardHeader>
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.income")}</p>
          </CardHeader>
          <CardBody>
            <p className="text-2xl font-semibold text-green-600">{formatMoney(data.income, currency, locale)}</p>
          </CardBody>
        </Card>
        <Card>
          <CardHeader>
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.expenses")}</p>
          </CardHeader>
          <CardBody>
            <p className="text-2xl font-semibold text-red-600">{formatMoney(data.expenses, currency, locale)}</p>
          </CardBody>
        </Card>
        <Card>
          <CardHeader>
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.savings")}</p>
          </CardHeader>
          <CardBody>
            <p className="text-2xl font-semibold">{formatMoney(data.savings, currency, locale)}</p>
          </CardBody>
        </Card>
        <Card>
          <CardHeader>
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.savings_rate")}</p>
          </CardHeader>
          <CardBody>
            <p className="text-2xl font-semibold">{data.savingsRate.toFixed(1)}%</p>
          </CardBody>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <h3 className="font-medium">{t("analytics.expenses")} — {title}</h3>
        </CardHeader>
        <CardBody className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={groupedData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="name" />
              <YAxis />
              <Tooltip />
              <Bar dataKey="amount" fill="#0ea5e9" />
            </BarChart>
          </ResponsiveContainer>
        </CardBody>
      </Card>
    </section>
  );
}
