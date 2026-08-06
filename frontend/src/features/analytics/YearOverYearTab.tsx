import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useYearOverYear } from "@/api/analytics";
import { useCategories } from "@/api/catalog";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { monthName } from "@/lib/dates";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabelByCode } from "@/lib/categoryLabel";

export function YearOverYearTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const navigate = useNavigate();
  const [month, setMonth] = useState(new Date().getMonth() + 1);
  const { data } = useYearOverYear(household.householdId, month, 5);
  const { data: categories = [] } = useCategories(household.householdId);

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="font-medium">{t("analytics.yoy")}</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.yoy_description")}</p>
          </div>
          <div>
            <Label className="text-xs">{t("common.month")}</Label>
            <Select value={month} onChange={(e) => setMonth(Number(e.target.value))}>
              {Array.from({ length: 12 }).map((_, i) => (
                <option key={i + 1} value={i + 1}>{monthName(i + 1, i18n.language)}</option>
              ))}
            </Select>
          </div>
        </div>
      </CardHeader>
      <CardBody>
        {!data || data.years.length === 0 ? (
          <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
        ) : (
          <div className="overflow-x-auto">
          <table className="w-full min-w-[28rem] text-sm">
            <thead className="text-left text-gray-500 dark:text-gray-400">
              <tr>
                <th className="py-2"></th>
                {data.years.map((y) => (
                  <th key={y} className="text-right">{y}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr className="border-t border-border">
                <td className="py-2 font-medium">{t("analytics.income")}</td>
                {data.years.map((y) => (
                  <td key={y} className="text-right">{formatMoney(data.incomeByYear[String(y)] ?? "0", household.currency, i18n.language)}</td>
                ))}
              </tr>
              <tr className="border-t border-border">
                <td className="py-2 font-medium">{t("analytics.expenses")}</td>
                {data.years.map((y) => (
                  <td key={y} className="text-right">{formatMoney(data.expensesByYear[String(y)] ?? "0", household.currency, i18n.language)}</td>
                ))}
              </tr>
              <tr className="border-t border-border">
                <td className="py-2 font-medium">{t("analytics.savings_rate")}</td>
                {data.years.map((y) => (
                  <td key={y} className="text-right">{(data.savingsRateByYear?.[String(y)] ?? 0).toFixed(1)}%</td>
                ))}
              </tr>
              {data.categories.map((c) => (
                <tr
                  key={c.categoryCode}
                  className="cursor-pointer border-t border-border hover:bg-gray-50 dark:hover:bg-row-hover/50"
                  onClick={() => navigate(`/transactions?categoryCode=${encodeURIComponent(c.categoryCode)}`)}
                >
                  <td className="py-2">
                    <span className="mr-1.5" aria-hidden>{categoryIcon(c.categoryCode)}</span>
                    {categoryLabelByCode(c.categoryCode, categories, t)}
                  </td>
                  {data.years.map((y) => (
                    <td key={y} className="text-right text-gray-600 dark:text-gray-300">{formatMoney(c.perYear[String(y)] ?? "0", household.currency, i18n.language)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </CardBody>
    </Card>
  );
}
