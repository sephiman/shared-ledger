import { useState } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { YearOverYearTab } from "./YearOverYearTab";
import { YearByYearTab } from "./YearByYearTab";
import { TrailingTab } from "./TrailingTab";
import { ForecastTab } from "./ForecastTab";
import { AllocationTab } from "./AllocationTab";
import { TopMoversTab } from "./TopMoversTab";
import { RecurringShareTab } from "./RecurringShareTab";
import { HeatmapTab } from "./HeatmapTab";

type Tab =
  | "yoy"
  | "yby"
  | "trailing"
  | "forecast"
  | "allocation"
  | "movers"
  | "recurring"
  | "heatmap";

export function AnalyticsPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>("yoy");
  const tabs: { id: Tab; label: string }[] = [
    { id: "yoy", label: t("analytics.yoy") },
    { id: "yby", label: t("analytics.yby") },
    { id: "trailing", label: t("analytics.trailing") },
    { id: "forecast", label: t("analytics.forecast") },
    { id: "allocation", label: t("analytics.allocation") },
    { id: "movers", label: t("analytics.top_movers") },
    { id: "recurring", label: t("analytics.recurring_share") },
    { id: "heatmap", label: t("analytics.heatmap") },
  ];
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("analytics.title")}</h1>
      <div className="flex flex-wrap gap-1 border-b border-border">
        {tabs.map((it) => (
          <button
            key={it.id}
            onClick={() => setTab(it.id)}
            className={cn(
              "px-3 py-2 text-sm font-medium",
              tab === it.id ? "border-b-2 border-primary text-primary" : "text-gray-600",
            )}
          >
            {it.label}
          </button>
        ))}
      </div>
      {tab === "yoy" && <YearOverYearTab />}
      {tab === "yby" && <YearByYearTab />}
      {tab === "trailing" && <TrailingTab />}
      {tab === "forecast" && <ForecastTab />}
      {tab === "allocation" && <AllocationTab />}
      {tab === "movers" && <TopMoversTab />}
      {tab === "recurring" && <RecurringShareTab />}
      {tab === "heatmap" && <HeatmapTab />}
    </div>
  );
}
