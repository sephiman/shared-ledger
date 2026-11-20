import { useTranslation } from "react-i18next";

/** "No spending recorded since August 2026 — ..." Shared between FIRE and Analytics spending review. */
export function StaleSpendingNotice({
  since,
  messageKey = "fire.stale_spending_notice",
}: {
  since: string | null;
  messageKey?: string;
}) {
  const { t, i18n } = useTranslation();
  if (!since) return null;
  const month = new Intl.DateTimeFormat(i18n.language, { month: "long", year: "numeric", timeZone: "UTC" }).format(
    new Date(`${since}-01T00:00:00Z`),
  );
  return (
    <p
      role="note"
      className="rounded-md border border-amber-300/70 bg-amber-50 px-2.5 py-1.5 text-xs text-amber-900 dark:border-amber-700/60 dark:bg-amber-950/40 dark:text-amber-200"
    >
      {t(messageKey, { month })}
    </p>
  );
}
