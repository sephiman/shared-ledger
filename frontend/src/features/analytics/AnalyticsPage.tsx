import { useTranslation } from "react-i18next";
import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { cn } from "@/lib/cn";
import { DailyTab } from "./DailyTab";
import { ExplorerTab } from "./ExplorerTab";
import { CompositionTab } from "./CompositionTab";
import { ChangesTab } from "./ChangesTab";
import { TrendsTab } from "./TrendsTab";

type TabDef = { slug: string; labelKey: string };

const TABS: TabDef[] = [
  { slug: "daily", labelKey: "analytics.daily" },
  { slug: "explorer", labelKey: "analytics.explorer" },
  { slug: "composition", labelKey: "analytics.composition" },
  { slug: "changes", labelKey: "analytics.changes" },
  { slug: "trends", labelKey: "analytics.trends" },
];

export function AnalyticsPage() {
  const { t } = useTranslation();
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("analytics.title")}</h1>
      <div className="sticky top-0 z-10 -mx-4 flex flex-wrap gap-1 border-b border-border bg-gray-50 px-4 dark:bg-gray-900">
        {TABS.map((it) => (
          <NavLink
            key={it.slug}
            to={`/analytics/${it.slug}`}
            end
            className={({ isActive }) =>
              cn(
                "px-3 py-2 text-sm font-medium",
                isActive
                  ? "border-b-2 border-primary text-primary"
                  : "text-gray-600 dark:text-gray-300",
              )
            }
          >
            {t(it.labelKey)}
          </NavLink>
        ))}
      </div>
      <Routes>
        <Route index element={<Navigate to="/analytics/daily" replace />} />
        <Route path="daily" element={<DailyTab />} />
        <Route path="explorer" element={<ExplorerTab />} />
        <Route path="composition" element={<CompositionTab />} />
        <Route path="changes" element={<ChangesTab />} />
        <Route path="trends" element={<TrendsTab />} />

        <Route path="yoy" element={<Navigate to="/analytics/trends#yoy" replace />} />
        <Route path="yby" element={<Navigate to="/analytics/trends#yby" replace />} />
        <Route path="trailing" element={<Navigate to="/analytics/trends#trailing" replace />} />
        <Route path="forecast" element={<Navigate to="/analytics/trends#forecast" replace />} />

        <Route path="allocation" element={<Navigate to="/analytics/composition#allocation" replace />} />
        <Route path="cost_of_living" element={<Navigate to="/analytics/composition#cost_of_living" replace />} />
        <Route path="recurring" element={<Navigate to="/analytics/composition#recurring" replace />} />

        <Route path="movers" element={<Navigate to="/analytics/changes#movers" replace />} />
        <Route path="heatmap" element={<Navigate to="/analytics/changes#heatmap" replace />} />

        <Route path="*" element={<Navigate to="/analytics/daily" replace />} />
      </Routes>
    </div>
  );
}
