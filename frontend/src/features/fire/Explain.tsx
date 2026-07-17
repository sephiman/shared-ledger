import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";

/**
 * Expandable "How is this calculated?" affordance. Every derived FIRE figure carries one,
 * with the formula instantiated with the household's own numbers (§ methodological transparency).
 */
export function Explain({ children, className }: { children: ReactNode; className?: string }) {
  const { t } = useTranslation();
  return (
    <details className={cn("mt-1 text-xs text-gray-500 dark:text-gray-400", className)}>
      <summary className="cursor-pointer select-none text-sky-600 hover:underline dark:text-sky-400">
        {t("fire.how_calculated")}
      </summary>
      <div className="mt-1 space-y-1">{children}</div>
    </details>
  );
}
