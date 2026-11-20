import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";

export function AiSeverityBadge({ severity, keyPrefix = "fire.ai.severity_" }: { severity: string; keyPrefix?: string }) {
  const { t } = useTranslation();
  const s = severity.toLowerCase();
  const tone =
    s === "critical"
      ? "border-red-200 bg-red-100 text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300"
      : s === "warning"
        ? "border-amber-200 bg-amber-100 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300"
        : "border-emerald-200 bg-emerald-100 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300";

  return (
    <span className={cn("inline-flex items-center rounded border px-1.5 py-0.5 text-xs font-medium", tone)}>
      {t(`${keyPrefix}${s}`, { defaultValue: s })}
    </span>
  );
}
