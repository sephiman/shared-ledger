import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useForecast } from "@/api/analytics";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { monthName } from "@/lib/dates";

export function ForecastTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const [horizon, setHorizon] = useState(6);
  const [window, setWindow] = useState(3);
  const { data } = useForecast(household.householdId, horizon, window);

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="font-medium">{t("analytics.forecast")}</p>
            <p className="mt-1 text-sm text-gray-500">{t("analytics.forecast_description")}</p>
          </div>
          <div className="flex items-end gap-2">
            <div>
              <Label className="text-xs">{t("analytics.horizon")}</Label>
              <Select value={horizon} onChange={(e) => setHorizon(Number(e.target.value))} title={t("analytics.horizon_help")}>
                {[1, 3, 6, 9, 12].map((h) => <option key={h} value={h}>{h}</option>)}
              </Select>
            </div>
            <div>
              <Label className="text-xs">{t("analytics.window")}</Label>
              <Select value={window} onChange={(e) => setWindow(Number(e.target.value))} title={t("analytics.window_help")}>
                {[3, 6, 12].map((w) => <option key={w} value={w}>{w}</option>)}
              </Select>
            </div>
          </div>
        </div>
      </CardHeader>
      <CardBody>
        {!data ? (
          <p className="text-gray-500">{t("common.loading")}</p>
        ) : data.categories.length === 0 ? (
          <p className="text-gray-500">{t("common.empty")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">{t("common.category")}</th>
                  {data.categories[0].projection.map((p) => (
                    <th key={`${p.year}-${p.month}`} className="text-right">{monthName(p.month, i18n.language, "short")} {p.year}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.categories.map((c) => (
                  <tr key={c.categoryCode} className="border-t border-border">
                    <td className="py-2">{t(`category.${c.categoryCode}`)}</td>
                    {c.projection.map((p) => (
                      <td key={`${p.year}-${p.month}`} className={`text-right ${p.source === "recurring" ? "text-primary" : "text-gray-700"}`}>
                        {formatMoney(p.projectedExpense, household.currency, i18n.language)}
                      </td>
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
