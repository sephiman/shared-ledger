import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";

export interface AiCitationItem {
  path: string;
  value: unknown;
  grounded: boolean;
  context?: string | null;
}

export function AiCitations({
  citations,
  formatLabel,
  formatValue = defaultFormatCitedValue,
  mismatchKey = "fire.ai.citation_mismatch",
}: {
  citations: AiCitationItem[];
  formatLabel: (c: AiCitationItem) => string;
  formatValue?: (c: AiCitationItem) => string;
  mismatchKey?: string;
}) {
  const { t } = useTranslation();
  if (citations.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-1.5">
      {citations.map((c, i) => {
        const label = formatLabel(c);
        const value = formatValue(c);
        const title = c.grounded ? c.path : `${c.path} — ${t(mismatchKey)}`;
        return (
          <li key={`${c.path}-${i}`}>
            <span
              title={title}
              className={cn(
                "inline-block rounded border px-1.5 py-0.5 text-xs tabular-nums",
                c.grounded
                  ? "border-emerald-200 text-emerald-700 dark:border-emerald-800 dark:text-emerald-300"
                  : "border-red-300 text-red-600 line-through dark:border-red-800 dark:text-red-400",
              )}
            >
              <span>{label}</span> = {value}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

export function defaultFormatCitedValue(c: AiCitationItem): string {
  if (typeof c.value === "string" || typeof c.value === "number") return String(c.value);
  return JSON.stringify(c.value);
}
