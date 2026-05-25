import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useCostOfLiving, type CostOfLivingCategoryRow } from "@/api/analytics";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { categoryIcon } from "@/lib/categoryGroup";

function CategoryBreakdown({
  title,
  amountHeader,
  rows,
  currency,
  locale,
}: {
  title: string;
  amountHeader: string;
  rows: CostOfLivingCategoryRow[];
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  return (
    <div>
      <p className="mb-2 text-sm font-medium">{title}</p>
      {rows.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-gray-500 dark:text-gray-400">
              <tr>
                <th className="py-2">{t("common.category")}</th>
                <th className="text-right">{amountHeader}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.categoryCode} className="border-t border-border">
                  <td className="py-2">
                    <span className="mr-1.5" aria-hidden>{categoryIcon(row.categoryCode)}</span>
                    {t(`category.${row.categoryCode}`)}
                  </td>
                  <td className="text-right tabular-nums">
                    {formatMoney(Number(row.monthlyAverage), currency, locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function CostOfLivingTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data, isLoading } = useCostOfLiving(household.householdId);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.cost_of_living")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            {t("analytics.cost_of_living_description")}
          </p>
        </CardHeader>
        <CardBody className="space-y-6">
          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && data.monthsAvailable === 0 && (
            <p className="text-gray-500 dark:text-gray-400">{t("analytics.cost_of_living_no_data")}</p>
          )}

          {!isLoading && data && data.monthsAvailable > 0 && (
            <>
              <div>
                <p className="text-sm uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  {t("analytics.essential_monthly")}
                </p>
                <p className="text-4xl font-semibold tabular-nums">
                  {formatMoney(Number(data.essentialMonthlyAverage), household.currency, i18n.language)}
                </p>
                <p className="mt-2 text-sm text-gray-500 dark:text-gray-400 tabular-nums">
                  {t("analytics.non_essential_monthly")}:{" "}
                  {formatMoney(Number(data.nonEssentialMonthlyAverage), household.currency, i18n.language)}
                </p>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400 tabular-nums">
                  {t("analytics.total_monthly")}:{" "}
                  {formatMoney(Number(data.totalMonthlyAverage), household.currency, i18n.language)}
                </p>
                <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
                  {t("analytics.essential_share", {
                    percent: formatNumber(data.essentialShare, i18n.language, 1),
                  })}
                </p>
                <p className="mt-2 text-xs text-gray-400 dark:text-gray-500">
                  {t("analytics.cost_of_living_window", { count: data.monthsAvailable })}
                </p>
              </div>

              <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                <CategoryBreakdown
                  title={t("analytics.cost_of_living_per_category")}
                  amountHeader={t("analytics.essential_monthly")}
                  rows={data.essentialCategories}
                  currency={household.currency}
                  locale={i18n.language}
                />
                <CategoryBreakdown
                  title={t("analytics.cost_of_living_per_category_non_essential")}
                  amountHeader={t("analytics.non_essential_monthly")}
                  rows={data.nonEssentialCategories}
                  currency={household.currency}
                  locale={i18n.language}
                />
              </div>
            </>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
