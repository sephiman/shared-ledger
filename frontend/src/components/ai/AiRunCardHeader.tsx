import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";

export function AiScopeChip({ label }: { label: string }) {
  return (
    <span className="rounded border border-border bg-gray-100 px-2 py-0.5 text-xs text-gray-700 dark:bg-chip dark:text-gray-200">
      {label}
    </span>
  );
}

export function AiRunCardHeader({
  createdAt,
  status,
  model,
  citationsUngrounded,
  groundedOkLabel,
  groundedOffLabel,
  copyReportLabel,
  copiedLabel,
  confirmDeleteMessage,
  chips,
  onToggle,
  onBuildMarkdown,
  onDelete,
}: {
  createdAt: string;
  status: string;
  model: string;
  citationsUngrounded: number | null;
  groundedOkLabel: string;
  groundedOffLabel: string;
  copyReportLabel: string;
  copiedLabel: string;
  confirmDeleteMessage: string;
  chips: ReactNode;
  onToggle: () => void;
  onBuildMarkdown: () => string;
  onDelete: () => void;
}) {
  const { t, i18n } = useTranslation();
  const [copied, setCopied] = useState(false);
  const ungrounded = citationsUngrounded ?? 0;

  const copyReport = () => {
    void navigator.clipboard.writeText(onBuildMarkdown()).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <button type="button" onClick={onToggle} className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-left">
        <span className="font-medium">{formatDate(createdAt, i18n.language, "d MMM yyyy HH:mm")}</span>
        {chips}
        {status === "completed" && (
          <span
            className={cn(
              "rounded px-2 py-0.5 text-xs",
              ungrounded === 0
                ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
                : "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
            )}
          >
            {ungrounded === 0 ? groundedOkLabel : groundedOffLabel}
          </span>
        )}
      </button>
      <div className="flex items-center gap-2">
        {status === "completed" && (
          <button
            type="button"
            onClick={copyReport}
            title={copyReportLabel}
            className="inline-flex items-center gap-1 rounded border border-border-strong bg-raised px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover dark:text-gray-200"
          >
            <span>{copied ? "✓" : "📋"}</span>
            <span>{copied ? copiedLabel : copyReportLabel}</span>
          </button>
        )}
        <span className="text-xs text-gray-400">{model}</span>
        <button
          type="button"
          aria-label={t("common.delete")}
          title={t("common.delete")}
          onClick={() => {
            if (window.confirm(confirmDeleteMessage)) onDelete();
          }}
          className="rounded p-1 text-gray-500 hover:bg-item-hover hover:text-red-600 dark:text-gray-300 dark:hover:text-red-400"
        >
          ✕
        </button>
      </div>
    </div>
  );
}
