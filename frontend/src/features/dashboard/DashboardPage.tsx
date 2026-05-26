import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useCostOfLiving,
  useDashboardExtras,
  useMonthDashboard,
  useYearDashboard,
  type CostOfLivingResponse,
  type DashboardExtras,
} from "@/api/analytics";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { groupIcon } from "@/lib/categoryGroup";
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
  const costOfLiving = useCostOfLiving(household.householdId);
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
        <CostOfLivingTile
          data={costOfLiving.data}
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

function CostOfLivingTile({
  data,
  currency,
  locale,
}: {
  data: CostOfLivingResponse | undefined;
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  if (!data) {
    return (
      <Card>
        <CardHeader>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.cost_of_living")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
        </CardBody>
      </Card>
    );
  }
  if (data.monthsAvailable === 0) {
    return (
      <Card>
        <CardHeader>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.cost_of_living")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-2xl font-semibold text-gray-400">—</p>
          <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("analytics.cost_of_living_no_data")}</p>
        </CardBody>
      </Card>
    );
  }
  return (
    <Card>
      <CardHeader>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.cost_of_living")}</p>
      </CardHeader>
      <CardBody>
        <p className="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">
          {t("analytics.essential_monthly")}
        </p>
        <p className="text-3xl font-semibold tabular-nums">
          {formatMoney(Number(data.essentialMonthlyAverage), currency, locale)}
        </p>
        <p
          className="mt-2 text-sm text-gray-500 dark:text-gray-400 tabular-nums"
          title={t("analytics.cost_of_living_window", { count: data.monthsAvailable })}
        >
          {t("analytics.total_monthly")}: {formatMoney(Number(data.totalMonthlyAverage), currency, locale)}
        </p>
      </CardBody>
    </Card>
  );
}

interface GroupBarTooltipProps {
  active?: boolean;
  label?: string | number;
  payload?: ReadonlyArray<{ value?: unknown }>;
  currency: string;
  locale: string;
}

function GroupBarTooltip({ active, label, payload, currency, locale }: GroupBarTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;
  const raw = payload[0]?.value;
  const value = typeof raw === "number" ? raw : typeof raw === "string" ? Number(raw) : 0;
  return (
    <div className="rounded-md border border-border bg-white p-2 text-xs shadow-sm dark:bg-gray-800">
      <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">{label}</p>
      <p className="font-medium tabular-nums text-gray-900 dark:text-gray-100">{formatMoney(value, currency, locale)}</p>
    </div>
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
    name: `${groupIcon(g.groupCode)} ${t(`category_group.${g.groupCode}`)}`,
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
              <Tooltip
                cursor={{ fill: "rgba(14,165,233,0.08)" }}
                content={(props) => <GroupBarTooltip {...props} currency={currency} locale={locale} />}
              />
              <Bar dataKey="amount" fill="#0ea5e9" />
            </BarChart>
          </ResponsiveContainer>
        </CardBody>
      </Card>
    </section>
  );
}
