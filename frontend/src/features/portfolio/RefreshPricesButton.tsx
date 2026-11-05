import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/primitives";
import { InfoTip } from "@/components/ui/InfoTip";
import { formatDate } from "@/lib/dates";
import { usePriceRefresh } from "./usePriceRefresh";

export function RefreshPricesButton({ householdId }: { householdId: string }) {
  const { t, i18n } = useTranslation();
  const refresh = usePriceRefresh(householdId);
  const time = (iso: string) => formatDate(iso, i18n.language, "p");

  return (
    <div className="flex flex-wrap items-center justify-end gap-x-2 gap-y-1">
      {refresh.nextAllowedAt && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          {refresh.lastFinishedAt
            ? t("wealth.refresh_cooldown", { last: time(refresh.lastFinishedAt), next: time(refresh.nextAllowedAt) })
            : t("wealth.refresh_next", { next: time(refresh.nextAllowedAt) })}
        </p>
      )}
      {refresh.failed && (
        <p role="alert" className="text-xs text-red-600 dark:text-red-400">
          {t("wealth.refresh_failed")}
        </p>
      )}
      {refresh.equitiesStaleOnly && (
        <InfoTip label={t("wealth.refresh_stale_only_label")}>{t("wealth.refresh_stale_only")}</InfoTip>
      )}
      <Button
        variant="secondary"
        className="py-1"
        disabled={refresh.refreshing || refresh.nextAllowedAt != null}
        onClick={refresh.start}
      >
        {refresh.refreshing ? t("portfolio.refreshing_prices") : t("wealth.refresh_now")}
      </Button>
    </div>
  );
}
