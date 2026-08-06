import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { HomeLogoLink } from "@/components/layout/HomeLogoLink";
import { UserMenu } from "@/components/layout/UserMenu";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useBankConfig, usePendingCount } from "@/api/banks";

const NAV = [
  { to: "/dashboard", key: "nav.dashboard" },
  { to: "/transactions", key: "nav.transactions" },
  { to: "/recurring", key: "nav.recurring" },
  { to: "/budgets", key: "nav.budgets" },
  { to: "/analytics", key: "nav.analytics" },
  { to: "/networth", key: "nav.wealth" },
  { to: "/fire", key: "nav.fire" },
  { to: "/settings", key: "nav.settings" },
];

export function AppShell() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  // Only query (and poll) the count once the household can actually ingest; otherwise it's 0.
  const { data: bankConfig } = useBankConfig(household.householdId);
  const hasBanks = (bankConfig?.credentialsConfigured ?? false) && (bankConfig?.connectionCount ?? 0) > 0;
  const pendingCount = usePendingCount(household.householdId, hasBanks).data?.count ?? 0;

  return (
    <div className="flex flex-col h-dvh">
      <header className="border-b border-border bg-surface">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
          <HomeLogoLink />
          <UserMenu />
        </div>
        <nav className="mx-auto max-w-6xl overflow-x-auto px-4">
          <ul className="flex gap-1 pb-2">
            {NAV.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  className={({ isActive }) =>
                    cn(
                      "inline-flex whitespace-nowrap rounded-md px-3 py-2 text-sm font-medium",
                      isActive
                        ? "bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300"
                        : "text-gray-600 hover:bg-item-hover dark:text-gray-300",
                    )
                  }
                >
                  {t(item.key)}
                  {item.to === "/transactions" && pendingCount > 0 && (
                    <span className="ml-1.5 inline-flex min-w-[1.25rem] items-center justify-center rounded-full bg-primary px-1.5 py-0.5 text-xs font-semibold text-primary-foreground">
                      {pendingCount}
                    </span>
                  )}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main className="flex-1 overflow-y-auto bg-canvas">
        <div className="mx-auto max-w-6xl px-4 pt-6 pb-[calc(env(safe-area-inset-bottom)+5rem)]">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
