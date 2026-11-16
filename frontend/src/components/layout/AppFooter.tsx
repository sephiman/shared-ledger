import { Link } from "react-router-dom";
import { Trans, useTranslation } from "react-i18next";
import { useTheme } from "@/lib/theme";
import logoDark from "@/assets/SharedLedgerDark.png";
import logoLight from "@/assets/SharedLedgerLight.png";

/** Subtle page footer rendered inside the scrollable main shell with brand mark, attribution, and manual link. */
export function AppFooter() {
  const { t } = useTranslation();
  const { resolvedTheme } = useTheme();
  const logoSrc = resolvedTheme === "light" ? logoLight : logoDark;

  return (
    <footer className="shrink-0 border-t border-border bg-surface/60">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-4 pb-[calc(env(safe-area-inset-bottom)+1rem)] text-xs text-gray-500 dark:text-gray-400">
        <div className="flex flex-wrap items-center gap-2.5">
          <Link
            to="/dashboard"
            aria-label={t("nav.go_home")}
            className="inline-flex shrink-0 items-center rounded focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
          >
            <img src={logoSrc} alt={t("app.name")} className="h-6 w-auto" />
          </Link>
          <span aria-hidden="true">·</span>
          <span>
            <Trans
              i18nKey="app.by_author"
              components={{
                author: (
                  <a
                    href="https://github.com/sephiman"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-medium text-gray-700 hover:text-primary hover:underline dark:text-gray-300"
                  />
                ),
              }}
            />
          </span>
        </div>
        <Link to="/manual" className="font-medium text-primary hover:underline">
          {t("nav.manual")}
        </Link>
      </div>
    </footer>
  );
}
