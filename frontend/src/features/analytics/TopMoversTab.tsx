import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useTopMovers, useYearsAvailable, type MoverRow } from "@/api/analytics";
import { useCategories, type Category } from "@/api/catalog";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { monthName } from "@/lib/dates";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabelByCode } from "@/lib/categoryLabel";

type Baseline = "year_ago" | "trailing6_avg";

export function TopMoversTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const now = new Date();
  const { data: years } = useYearsAvailable(household.householdId);
  const [year, setYear] = useState<number>(now.getFullYear());
  const [month, setMonth] = useState<number>(now.getMonth() + 1);
  const [baseline, setBaseline] = useState<Baseline>("year_ago");

  const { data, isLoading } = useTopMovers(household.householdId, year, month, baseline);
  const { data: categories = [] } = useCategories(household.householdId);

  const yearOptions = useMemo(() => {
    const fromServer = years?.years ?? [];
    if (fromServer.length === 0) return [now.getFullYear()];
    return fromServer;
  }, [years, now]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.top_movers")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.top_movers_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <div>
              <Label>{t("analytics.select_year")}</Label>
              <Select value={year} onChange={(e) => setYear(Number(e.target.value))}>
                {yearOptions.map((y) => (
                  <option key={y} value={y}>{y}</option>
                ))}
              </Select>
            </div>
            <div>
              <Label>{t("analytics.select_month")}</Label>
              <Select value={month} onChange={(e) => setMonth(Number(e.target.value))}>
                {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
                  <option key={m} value={m}>{monthName(m, i18n.language, "long")}</option>
                ))}
              </Select>
            </div>
            <div>
              <Label>{t("analytics.baseline")}</Label>
              <Select value={baseline} onChange={(e) => setBaseline(e.target.value as Baseline)}>
                <option value="year_ago">{t("analytics.baseline_year_ago")}</option>
                <option value="trailing6_avg">{t("analytics.baseline_trailing6")}</option>
              </Select>
            </div>
          </div>

          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && (
            <div className="space-y-4">
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <MoverList
                  title={t("analytics.increases")}
                  rows={data.increases}
                  tone="up"
                  categories={categories}
                  currency={household.currency}
                  locale={i18n.language}
                />
                <MoverList
                  title={t("analytics.decreases")}
                  rows={data.decreases}
                  tone="down"
                  categories={categories}
                  currency={household.currency}
                  locale={i18n.language}
                />
              </div>

              {data.newActivity.length > 0 && (
                <div className="space-y-2">
                  <h3 className="text-sm font-medium">{t("analytics.new_activity")}</h3>
                  <p className="text-xs text-gray-500 dark:text-gray-400">{t("analytics.new_activity_description")}</p>
                  <MoverTable
                    rows={data.newActivity}
                    tone="new"
                    categories={categories}
                    currency={household.currency}
                    locale={i18n.language}
                  />
                </div>
              )}

              {data.increases.length === 0 && data.decreases.length === 0 && data.newActivity.length === 0 && (
                <p className="text-gray-500 dark:text-gray-400">{t("analytics.no_movers")}</p>
              )}

              {(data.increases.length > 0 || data.decreases.length > 0) && (
                <p className="text-sm text-gray-600 dark:text-gray-300">
                  {t("analytics.totals_summary", {
                    increase: formatMoney(data.totalIncrease, household.currency, i18n.language),
                    decrease: formatMoney(data.totalDecrease, household.currency, i18n.language),
                  })}
                </p>
              )}
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function MoverList({
  title,
  rows,
  tone,
  categories,
  currency,
  locale,
}: {
  title: string;
  rows: MoverRow[];
  tone: "up" | "down" | "new";
  categories: Category[];
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-medium">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
      ) : (
        <MoverTable rows={rows} tone={tone} categories={categories} currency={currency} locale={locale} />
      )}
    </div>
  );
}

function MoverTable({
  rows,
  tone,
  categories,
  currency,
  locale,
}: {
  rows: MoverRow[];
  tone: "up" | "down" | "new";
  categories: Category[];
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  const arrow = tone === "up" ? "▲" : tone === "down" ? "▼" : "•";
  const arrowColor = tone === "up" ? "text-red-600" : tone === "down" ? "text-green-600" : "text-sky-600";
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-left text-gray-500 dark:text-gray-400">
          <tr>
            <th className="py-2">{t("common.category")}</th>
            <th className="text-right">{t("analytics.period_amount")}</th>
            <th className="text-right">{t("analytics.baseline_amount")}</th>
            <th className="text-right">{t("analytics.change")}</th>
            <th className="text-right">{t("analytics.percent_change")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.categoryCode} className="border-t border-border">
              <td className="py-2">
                <span className={`mr-2 ${arrowColor}`}>{arrow}</span>
                <span className="mr-1.5" aria-hidden>{categoryIcon(r.categoryCode)}</span>
                {categoryLabelByCode(r.categoryCode, categories, t)}
              </td>
              <td className="text-right">{formatMoney(r.periodAmount, currency, locale)}</td>
              <td className="text-right">{formatMoney(r.baselineAmount, currency, locale)}</td>
              <td className="text-right">{formatMoney(r.deltaAbs, currency, locale)}</td>
              <td className="text-right">
                {r.deltaPct === null ? "—" : `${formatNumber(r.deltaPct, locale, 1)}%`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
