import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { useTheme } from "@/lib/theme";
import { UserMenu } from "@/components/layout/UserMenu";

const NAV = [
  { to: "/dashboard", key: "nav.dashboard" },
  { to: "/transactions", key: "nav.transactions" },
  { to: "/recurring", key: "nav.recurring" },
  { to: "/budgets", key: "nav.budgets" },
  { to: "/analytics", key: "nav.analytics" },
  { to: "/networth", key: "nav.networth" },
  { to: "/fire", key: "nav.fire" },
  { to: "/settings", key: "nav.settings" },
];

export function AppShell() {
  const { t } = useTranslation();
  const { resolvedTheme } = useTheme();
  const logoSrc = resolvedTheme === "dark" ? "/SharedLedgerDark.png" : "/SharedLedgerLight.png";

  return (
    <div className="flex flex-col h-screen">
      <header className="border-b border-border bg-white dark:bg-gray-800 dark:border-gray-700">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
          <img src={logoSrc} alt={t("app.name")} className="h-10 w-auto shrink-0" />
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
                        : "text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700",
                    )
                  }
                >
                  {t(item.key)}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main className="flex-1 overflow-y-auto bg-gray-50 dark:bg-gray-900">
        <div className="mx-auto max-w-6xl px-4 py-6">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
