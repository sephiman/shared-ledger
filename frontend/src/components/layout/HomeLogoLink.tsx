import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useTheme } from "@/lib/theme";
// Imported rather than referenced from public/, so Vite content-hashes them into /assets/ and a new
// artwork gets a new URL. Served from public/ the filename never changes, and browsers kept serving
// a stale logo from heuristic cache for days.
import logoDark from "@/assets/SharedLedgerDark.png";
import logoLight from "@/assets/SharedLedgerLight.png";

/** The header logo, doubling as the way back Home from anywhere in the app. A real anchor, so focus
 *  and Enter come from the browser; react-router replaces (instead of pushing) when we are already
 *  on the target, so repeated clicks never pile up history entries. */
export function HomeLogoLink() {
  const { t } = useTranslation();
  const { resolvedTheme } = useTheme();
  const logoSrc = resolvedTheme === "light" ? logoLight : logoDark;

  return (
    <Link
      to="/dashboard"
      aria-label={t("nav.go_home")}
      className="inline-flex shrink-0 cursor-pointer rounded-md focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
    >
      <img src={logoSrc} alt={t("app.name")} className="h-10 w-auto" />
    </Link>
  );
}
