import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useAuth } from "@/auth/AuthContext";
import { cn } from "@/lib/cn";
import { useTheme, type ThemePreference } from "@/lib/theme";

function initialsFor(email: string): string {
  const local = email.split("@")[0] ?? "";
  const parts = local.split(/[._-]+/).filter(Boolean);
  if (parts.length === 0) return email.slice(0, 1).toUpperCase() || "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

export function UserMenu() {
  const { user, logout, activeHouseholdId, setActiveHouseholdId } = useAuth();
  const { t, i18n } = useTranslation();
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  if (!user) return null;

  const initials = initialsFor(user.email);
  const languages: Array<{ code: "en" | "es"; label: string }> = [
    { code: "en", label: "EN" },
    { code: "es", label: "ES" },
  ];
  const activeLang = i18n.language.startsWith("es") ? "es" : "en";
  const activeHousehold = user.households.find((h) => h.householdId === activeHouseholdId);
  const showHouseholdSwitcher = user.households.length > 1;
  const themes: Array<{ value: ThemePreference; label: string }> = [
    { value: "light", label: t("settings.theme_light") },
    { value: "dark", label: t("settings.theme_dark") },
    { value: "oled", label: t("settings.theme_oled") },
    { value: "system", label: t("settings.theme_system") },
  ];

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        aria-label={t("user.menu_aria")}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-sky-600 focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1 dark:focus:ring-offset-gray-800"
      >
        {initials}
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 z-50 mt-2 w-64 rounded-md border border-border bg-overlay shadow-lg"
        >
          <div className="border-b border-border px-4 py-3">
            <p className="text-xs text-gray-500 dark:text-gray-400">{t("user.signed_in_as")}</p>
            <p className="truncate text-sm font-medium text-gray-900 dark:text-gray-100">{user.email}</p>
            {activeHousehold && (
              <p className="mt-1 truncate text-xs text-gray-500 dark:text-gray-400">
                {t("household.label")}: <span className="font-medium text-gray-700 dark:text-gray-300">{activeHousehold.name}</span>
              </p>
            )}
          </div>
          {showHouseholdSwitcher && (
            <div className="border-b border-border px-4 py-3">
              <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("household.switch_aria")}</p>
              <ul className="-mx-2 max-h-48 overflow-y-auto">
                {user.households.map((h) => {
                  const isActive = h.householdId === activeHouseholdId;
                  return (
                    <li key={h.householdId}>
                      <button
                        type="button"
                        role="menuitemradio"
                        aria-checked={isActive}
                        onClick={() => {
                          setActiveHouseholdId(h.householdId);
                          setOpen(false);
                        }}
                        className={cn(
                          "flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm",
                          isActive
                            ? "bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300"
                            : "text-gray-700 hover:bg-item-hover dark:text-gray-200",
                        )}
                      >
                        <span className="truncate">
                          {h.householdId === user.defaultHouseholdId ? `★ ${h.name}` : h.name}
                        </span>
                        {isActive && <span aria-hidden="true">✓</span>}
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
          <div className="border-b border-border px-4 py-3">
            <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("common.language")}</p>
            <div className="inline-flex rounded-md border border-border-strong">
              {languages.map((lang) => (
                <button
                  key={lang.code}
                  type="button"
                  onClick={() => void i18n.changeLanguage(lang.code)}
                  className={cn(
                    "px-3 py-1 text-sm font-medium transition-colors first:rounded-l-md last:rounded-r-md",
                    activeLang === lang.code
                      ? "bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300"
                      : "text-gray-600 hover:bg-row-hover dark:text-gray-300",
                  )}
                >
                  {lang.label}
                </button>
              ))}
            </div>
          </div>
          <div className="border-b border-border px-4 py-3">
            <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("settings.theme")}</p>
            {/* Two columns: four labels in a 256px menu would squeeze "Sistema" past its own width. */}
            <div className="grid grid-cols-2 overflow-hidden rounded-md border border-border-strong">
              {themes.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  onClick={() => setTheme(opt.value)}
                  className={cn(
                    "px-2 py-1.5 text-sm font-medium transition-colors",
                    theme === opt.value
                      ? "bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300"
                      : "text-gray-600 hover:bg-row-hover dark:text-gray-300",
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
          <button
            type="button"
            role="menuitem"
            onClick={async () => {
              setOpen(false);
              await logout();
              navigate("/login");
            }}
            className="block w-full px-4 py-3 text-left text-sm font-medium text-gray-700 hover:bg-item-hover dark:text-gray-200"
          >
            {t("nav.logout")}
          </button>
        </div>
      )}
    </div>
  );
}
