import { lazy, Suspense, type ReactNode } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { LoginPage } from "@/auth/LoginPage";
import { RegisterPage } from "@/auth/RegisterPage";
import { RequireAuth } from "@/auth/RequireAuth";
import { AppShell } from "@/components/layout/AppShell";
import { ToastHost } from "@/components/ui/ToastHost";

const DashboardPage = lazy(() => import("@/features/dashboard/DashboardPage").then((m) => ({ default: m.DashboardPage })));
const TransactionsPage = lazy(() => import("@/features/transactions/TransactionsPage").then((m) => ({ default: m.TransactionsPage })));
const RecurringPage = lazy(() => import("@/features/recurring/RecurringPage").then((m) => ({ default: m.RecurringPage })));
const BudgetsPage = lazy(() => import("@/features/budgets/BudgetsPage").then((m) => ({ default: m.BudgetsPage })));
const NetWorthPage = lazy(() => import("@/features/networth/NetWorthPage").then((m) => ({ default: m.NetWorthPage })));
const AnalyticsPage = lazy(() => import("@/features/analytics/AnalyticsPage").then((m) => ({ default: m.AnalyticsPage })));
const FirePage = lazy(() => import("@/features/fire/FirePage").then((m) => ({ default: m.FirePage })));
const SettingsPage = lazy(() => import("@/features/settings/SettingsPage").then((m) => ({ default: m.SettingsPage })));
const DataImportPage = lazy(() => import("@/features/settings/DataImportPage").then((m) => ({ default: m.DataImportPage })));

function LazyFallback() {
  const { t } = useTranslation();
  return <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>;
}

function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<LazyFallback />}>{children}</Suspense>;
}

export default function App() {
  return (
    <>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          element={
            <RequireAuth>
              <AppShell />
            </RequireAuth>
          }
        >
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Lazy><DashboardPage /></Lazy>} />
          <Route path="/transactions" element={<Lazy><TransactionsPage /></Lazy>} />
          <Route path="/recurring" element={<Lazy><RecurringPage /></Lazy>} />
          <Route path="/budgets" element={<Lazy><BudgetsPage /></Lazy>} />
          <Route path="/networth/*" element={<Lazy><NetWorthPage /></Lazy>} />
          {/* Portfolio was merged into the Wealth hub; keep old links working. */}
          <Route path="/portfolio/*" element={<Navigate to="/networth?tab=holdings" replace />} />
          <Route path="/analytics/*" element={<Lazy><AnalyticsPage /></Lazy>} />
          <Route path="/fire" element={<Lazy><FirePage /></Lazy>} />
          <Route path="/settings" element={<Lazy><SettingsPage /></Lazy>} />
          <Route path="/settings/import" element={<Lazy><DataImportPage /></Lazy>} />
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      <ToastHost />
    </>
  );
}
