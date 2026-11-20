import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import type { ProjectionScenario } from "@/api/questions";
import { TabBar } from "@/components/ui/TabBar";
import { DailyTab } from "./DailyTab";
import { ExplorerTab } from "./ExplorerTab";
import { CompositionTab } from "./CompositionTab";
import { ChangesTab } from "./ChangesTab";
import { TrendsTab } from "./TrendsTab";
import { MoneyFlowTab } from "./MoneyFlowTab";
import { SpendingReviewTab } from "./review/SpendingReviewTab";

type TabDef = { slug: string; labelKey: string };

const TABS: TabDef[] = [
  { slug: "daily", labelKey: "analytics.daily" },
  { slug: "explorer", labelKey: "analytics.explorer" },
  { slug: "composition", labelKey: "analytics.composition" },
  { slug: "changes", labelKey: "analytics.changes" },
  { slug: "trends", labelKey: "analytics.trends" },
  { slug: "money-flow", labelKey: "analytics.money_flow" },
  { slug: "review", labelKey: "analytics.spending_review" },
];

export function AnalyticsPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const current = location.pathname.split("/")[2] ?? "";
  const activeTab = TABS.some((it) => it.slug === current) ? current : "daily";
  const items = TABS.map((it) => ({ value: it.slug, label: t(it.labelKey) }));
  const [pendingScenario, setPendingScenario] = useState<ProjectionScenario | null>(
    () => (location.state as { initialScenario?: ProjectionScenario } | null)?.initialScenario ?? null,
  );

  useEffect(() => {
    const incoming = (location.state as { initialScenario?: ProjectionScenario } | null)?.initialScenario;
    if (!incoming) return;
    setPendingScenario(incoming);
    navigate(location.pathname + location.search, { replace: true, state: null });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.state]);

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("analytics.title")}</h1>
      <TabBar items={items} value={activeTab} onChange={(slug) => navigate(`/analytics/${slug}`)} ariaLabel={t("analytics.title")} />
      <Routes>
        <Route index element={<Navigate to="/analytics/daily" replace />} />
        <Route path="daily" element={<DailyTab />} />
        <Route path="explorer" element={<ExplorerTab />} />
        <Route path="composition" element={<CompositionTab />} />
        <Route path="changes" element={<ChangesTab />} />
        <Route path="trends" element={<TrendsTab />} />
        <Route
          path="money-flow"
          element={
            <MoneyFlowTab
              initialScenario={pendingScenario}
              onInitialScenarioApplied={() => setPendingScenario(null)}
            />
          }
        />
        <Route path="review" element={<SpendingReviewTab />} />

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
