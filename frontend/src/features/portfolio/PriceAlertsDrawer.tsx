import { useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import {
  useCancelPriceAlert,
  useDeletePriceAlert,
  usePriceAlerts,
  type HoldingSummary,
  type PriceAlert,
} from "@/api/portfolio";
import { Button, FieldError } from "@/components/ui/primitives";
import { formatPrice } from "@/lib/money";
import { formatAlertTimestamp } from "./priceAlerts";

const MAX_DELIVERY_ATTEMPTS = 3;

interface Props {
  householdId: string;
  holdings: HoldingSummary[];
  displayTimezone?: string;
  onEdit: (alert: PriceAlert, holdingRow: HoldingSummary) => void;
  onClose: () => void;
}

type Filter = "all" | "active" | "finished";

export function PriceAlertsDrawer({ householdId, holdings, displayTimezone, onEdit, onClose }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const { data: alerts = [], isLoading } = usePriceAlerts(householdId, true);
  const cancelAlert = useCancelPriceAlert(householdId);
  const deleteAlert = useDeletePriceAlert(householdId);
  const [filter, setFilter] = useState<Filter>("all");
  const [error, setError] = useState<string | null>(null);

  const activeAlerts = alerts.filter((a) => a.status === "ACTIVE");
  const finishedAlerts = alerts.filter((a) => a.status !== "ACTIVE");
  const visible = filter === "active" ? activeAlerts : filter === "finished" ? finishedAlerts : alerts;

  const holdingMap = new Map(holdings.map((h) => [h.holding.id, h]));

  async function handleCancel(alertId: string) {
    setError(null);
    try {
      await cancelAlert.mutateAsync(alertId);
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  }

  async function handleDelete(alertId: string) {
    setError(null);
    try {
      await deleteAlert.mutateAsync(alertId);
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  }

  return (
    <div
      className="fixed inset-0 z-40 flex justify-end bg-black/40"
      role="dialog"
      aria-modal="true"
      aria-labelledby="price-alerts-drawer-title"
      onClick={onClose}
    >
      <div
        className="h-full w-full max-w-xl overflow-y-auto bg-panel p-4 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between gap-2">
          <h2 id="price-alerts-drawer-title" className="text-lg font-semibold">
            {t("portfolio.alerts.drawer_title")}
          </h2>
          <Button variant="ghost" aria-label={t("common.cancel")} onClick={onClose}>✕</Button>
        </div>

        <div className="mb-3 flex flex-wrap gap-1.5">
          {(["all", "active", "finished"] as Filter[]).map((f) => {
            const count = f === "all" ? alerts.length : f === "active" ? activeAlerts.length : finishedAlerts.length;
            const selected = filter === f;
            return (
              <button
                key={f}
                type="button"
                aria-pressed={selected}
                onClick={() => setFilter(f)}
                className={`rounded-full px-3 py-1 text-xs font-medium ${
                  selected
                    ? "bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900"
                    : "border border-border bg-surface text-gray-700 hover:bg-raised-hover dark:text-gray-300"
                }`}
              >
                {t(`portfolio.alerts.filter_${f}`, { count })}
              </button>
            );
          })}
        </div>

        <FieldError message={error} />

        {isLoading ? (
          <p className="text-sm text-gray-500">{t("common.loading")}</p>
        ) : visible.length === 0 ? (
          <p className="rounded-md border border-border bg-surface p-4 text-sm text-gray-500 dark:text-gray-400">
            {t("portfolio.alerts.empty")}
          </p>
        ) : (
          <ul className="space-y-2.5">
            {visible.map((alert) => {
              const row = holdingMap.get(alert.holdingId);
              const thresholdText = formatPrice(alert.threshold, alert.currency, locale);
              const condText = t(
                alert.direction === "ABOVE" ? "portfolio.alerts.condition_above" : "portfolio.alerts.condition_below",
                { threshold: thresholdText },
              );
              const statusBadge = statusBadgeClass(alert.status);
              return (
                <li
                  key={alert.id}
                  className="rounded-md border border-border bg-surface p-3 text-sm"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold">{alert.symbol}</span>
                        {alert.label && (
                          <span className="truncate text-xs text-gray-500 dark:text-gray-400">{alert.label}</span>
                        )}
                        <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${statusBadge}`}>
                          {t(`portfolio.alerts.status_${alert.status}`)}
                        </span>
                      </div>
                      <p className="mt-1 font-mono text-sm font-medium">{condText}</p>
                      {alert.note && (
                        <p className="mt-1 text-xs text-gray-700 dark:text-gray-200">{alert.note}</p>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      {alert.status === "ACTIVE" && row && (
                        <Button
                          variant="ghost"
                          className="px-2 py-1 text-xs"
                          onClick={() => onEdit(alert, row)}
                        >
                          {t("common.edit")}
                        </Button>
                      )}
                      {alert.status === "ACTIVE" ? (
                        <Button
                          variant="secondary"
                          className="px-2 py-1 text-xs"
                          disabled={cancelAlert.isPending}
                          onClick={() => void handleCancel(alert.id)}
                        >
                          {t("portfolio.alerts.cancel_btn")}
                        </Button>
                      ) : (
                        <Button
                          variant="ghost"
                          className="px-2 py-1 text-xs text-red-600 dark:text-red-400"
                          disabled={deleteAlert.isPending}
                          onClick={() => void handleDelete(alert.id)}
                        >
                          {t("portfolio.alerts.delete_btn")}
                        </Button>
                      )}
                    </div>
                  </div>

                  <div className="mt-2 space-y-0.5 text-xs text-gray-500 dark:text-gray-400">
                    <p>
                      {t("portfolio.alerts.created_at", {
                        date: formatAlertTimestamp(alert.createdAt, locale, displayTimezone),
                      })}
                      {alert.createdByEmail && ` · ${t("portfolio.alerts.created_by", { email: alert.createdByEmail })}`}
                    </p>
                    <p>
                      {alert.expiresAt
                        ? t("portfolio.alerts.expires_at", {
                            date: formatAlertTimestamp(alert.expiresAt, locale, displayTimezone),
                          })
                        : t("portfolio.alerts.no_expiry")}
                    </p>
                    {alert.status === "FIRED" && alert.firedPrice != null && (
                      <p className="font-medium text-emerald-700 dark:text-emerald-400">
                        {t("portfolio.alerts.fired_at", {
                          price: formatPrice(alert.firedPrice, alert.currency, locale),
                          when: formatAlertTimestamp(alert.firedPriceAsOf ?? alert.firedAt!, locale, displayTimezone),
                        })}
                      </p>
                    )}
                    {alert.status === "FIRED" && (
                      <DeliveryStatusLine alert={alert} locale={locale} displayTimezone={displayTimezone} />
                    )}
                    {alert.status === "CANCELLED" && alert.cancelledAt && (
                      <p className="text-amber-700 dark:text-amber-300">
                        {t("portfolio.alerts.cancelled_at", {
                          date: formatAlertTimestamp(alert.cancelledAt, locale, displayTimezone),
                        })}
                        {alert.cancelReason && ` · ${t(`portfolio.alerts.cancel_reason_${alert.cancelReason}`)}`}
                      </p>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

function DeliveryStatusLine({
  alert,
  locale,
  displayTimezone,
}: {
  alert: PriceAlert;
  locale: string;
  displayTimezone?: string;
}) {
  const { t } = useTranslation();
  if (alert.deliverySentAt) {
    return (
      <p className="text-emerald-700 dark:text-emerald-400">
        {t("portfolio.alerts.delivery_sent", {
          when: formatAlertTimestamp(alert.deliverySentAt, locale, displayTimezone),
        })}
      </p>
    );
  }
  if (alert.deliveryExhausted) {
    return (
      <p className="text-red-600 dark:text-red-400">
        {t("portfolio.alerts.delivery_failed", { error: alert.deliveryLastError ?? "unknown error" })}
      </p>
    );
  }
  return (
    <p className="text-amber-700 dark:text-amber-300">
      {alert.deliveryLastError
        ? t("portfolio.alerts.delivery_pending_error", {
            attempts: alert.deliveryAttempts,
            max: MAX_DELIVERY_ATTEMPTS,
            error: alert.deliveryLastError,
          })
        : t("portfolio.alerts.delivery_pending", {
            attempts: alert.deliveryAttempts,
            max: MAX_DELIVERY_ATTEMPTS,
          })}
    </p>
  );
}

function statusBadgeClass(status: PriceAlert["status"]): string {
  switch (status) {
    case "ACTIVE":
      return "bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-200";
    case "FIRED":
      return "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200";
    case "EXPIRED":
      return "bg-gray-200 text-gray-700 dark:bg-raised dark:text-gray-300";
    case "CANCELLED":
      return "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200";
  }
}
