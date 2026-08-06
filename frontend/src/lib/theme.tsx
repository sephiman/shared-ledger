import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export type ThemePreference = "light" | "dark" | "oled" | "system";
export type ResolvedTheme = "light" | "dark" | "oled";

const STORAGE_KEY = "theme";

/** OLED keeps the mobile address bar black instead of letting a bright sky bar sit above a #000 app. */
const BRAND_THEME_COLOR = "#0ea5e9";
const OLED_THEME_COLOR = "#000000";

interface ThemeContextValue {
  theme: ThemePreference;
  resolvedTheme: ResolvedTheme;
  setTheme: (theme: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

function readStored(): ThemePreference {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === "light" || v === "dark" || v === "oled" || v === "system") return v;
  } catch {}
  return "system";
}

function systemPrefersDark(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/** The OS preference only ever yields light or dark: OLED is reachable by explicit choice alone, and
 *  once chosen it is fixed, exactly like an explicit light or dark pick. */
function resolve(theme: ThemePreference): ResolvedTheme {
  if (theme === "system") return systemPrefersDark() ? "dark" : "light";
  return theme;
}

function applyToDocument(resolved: ResolvedTheme) {
  const root = document.documentElement;
  // OLED carries `dark` as well, so every dark: variant (text, accents) still applies and only the
  // surface and border tokens are re-pointed.
  root.classList.toggle("dark", resolved === "dark" || resolved === "oled");
  root.classList.toggle("oled", resolved === "oled");
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", resolved === "oled" ? OLED_THEME_COLOR : BRAND_THEME_COLOR);
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemePreference>(() => readStored());
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>(() => resolve(readStored()));

  useEffect(() => {
    const resolved = resolve(theme);
    setResolvedTheme(resolved);
    applyToDocument(resolved);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {}
  }, [theme]);

  useEffect(() => {
    if (theme !== "system") return;
    const mql = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => {
      const resolved: ResolvedTheme = mql.matches ? "dark" : "light";
      setResolvedTheme(resolved);
      applyToDocument(resolved);
    };
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, [theme]);

  const value = useMemo<ThemeContextValue>(
    () => ({ theme, resolvedTheme, setTheme: setThemeState }),
    [theme, resolvedTheme],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within a ThemeProvider");
  return ctx;
}
