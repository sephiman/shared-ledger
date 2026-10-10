import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { useAuth } from "@/auth/AuthContext";
import { useBankConfig, useBankConnections } from "@/api/banks";
import { useUpdateDismissedConsentNotices } from "@/api/settings";
import { cn } from "@/lib/cn";
import { consentAlerts, consentNoticeKey, dismissedAfterHiding, type ConsentAlert } from "./consentUrgency";

/** A standing condition, not an event: stays under the header on every page until re-linked or dismissed.
 *  Every member sees it — anyone can nudge the holder, though only the holder can pass the bank's SCA. */
export function ConsentExpiryBanner({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { data: config } = useBankConfig(householdId);
  const { data: connections = [] } = useBankConnections(householdId, (config?.connectionCount ?? 0) > 0);
  const dismiss = useUpdateDismissedConsentNotices();

  const alerts = consentAlerts(connections, new Date());
  const dismissed = new Set(user?.dismissedConsentNotices ?? []);
  const visible = alerts.filter((a) => !dismissed.has(consentNoticeKey(a.connection)));
  if (visible.length === 0 || dismiss.isPending) return null;

  const anyExpired = visible.some((a) => a.urgency.kind === "expired");
  const message = bannerMessage(visible, t);

  return (
    <div
      role="status"
      className={cn(
        "border-b text-sm",
        anyExpired
          ? "border-red-300 bg-red-50 text-red-800 dark:border-red-800 dark:bg-red-950/40 dark:text-red-200"
          : "border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200",
      )}
    >
      <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-1.5">
        <p className="min-w-0 flex-1 truncate" title={message}>{message}</p>
        <Link to="/settings#banks" className="shrink-0 font-medium underline underline-offset-2">
          {t("banks.consent_banner_action")}
        </Link>
        <button
          type="button"
          aria-label={t("banks.consent_banner_dismiss")}
          className="shrink-0 rounded px-1 leading-none opacity-70 hover:opacity-100"
          onClick={() => dismiss.mutate(dismissedAfterHiding(alerts))}
        >
          ✕
        </button>
      </div>
    </div>
  );
}

function bannerMessage(alerts: ConsentAlert[], t: TFunction): string {
  if (alerts.length > 1) {
    return alerts.some((a) => a.urgency.kind === "expired")
      ? t("banks.consent_banner_several_expired", { count: alerts.length })
      : t("banks.consent_banner_several", { count: alerts.length });
  }
  const { connection, urgency } = alerts[0];
  const label = connection.label ?? connection.aspspName;
  if (urgency.kind === "expired") return t("banks.consent_banner_expired", { label });
  if (urgency.days === 0) return t("banks.consent_banner_today", { label });
  return t("banks.consent_banner_days", { label, count: urgency.days });
}
