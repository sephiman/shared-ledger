import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { cn } from "@/lib/cn";
import { useTheme } from "@/lib/theme";
import { Button } from "@/components/ui/primitives";

const NAV = [
  { to: "/dashboard", key: "nav.dashboard" },
  { to: "/transactions", key: "nav.transactions" },
  { to: "/recurring", key: "nav.recurring" },
  { to: "/budgets", key: "nav.budgets" },
  { to: "/networth", key: "nav.networth" },
  { to: "/analytics", key: "nav.analytics" },
  { to: "/fire", key: "nav.fire" },
  { to: "/settings", key: "nav.settings" },
];

export function AppShell() {
  const { user, logout, activeHouseholdId, setActiveHouseholdId } = useAuth();
  const { t, i18n } = useTranslation();
  const { resolvedTheme } = useTheme();
  const navigate = useNavigate();
  const logoSrc = resolvedTheme === "dark" ? "/SharedLedgerDark.png" : "/SharedLedgerLight.png";

  return (
    <div className="flex flex-col h-screen">
      <header className="border-b border-border bg-white dark:bg-gray-800 dark:border-gray-700">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <div className="flex items-center gap-4">
            <img src={logoSrc} alt={t("app.name")} className="h-10 w-auto" />
            {user && user.households.length > 1 && (
              <select
                aria-label={t("household.switch_aria")}
                value={activeHouseholdId ?? ""}
                onChange={(e) => setActiveHouseholdId(e.target.value)}
                className="rounded border border-border bg-white px-2 py-1 text-sm dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600"
              >
                {user.households.map((h) => (
                  <option key={h.householdId} value={h.householdId}>
                    {h.householdId === user.defaultHouseholdId ? `★ ${h.name}` : h.name}
                  </option>
                ))}
              </select>
            )}
          </div>
          <div className="flex items-center gap-2">
            <select
              value={i18n.language}
              onChange={(e) => void i18n.changeLanguage(e.target.value)}
              className="rounded border border-border bg-white px-2 py-1 text-sm dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600"
            >
              <option value="en">EN</option>
              <option value="es">ES</option>
            </select>
            <Button
              variant="ghost"
              onClick={async () => {
                await logout();
                navigate("/login");
              }}
            >
              {t("nav.logout")}
            </Button>
          </div>
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
