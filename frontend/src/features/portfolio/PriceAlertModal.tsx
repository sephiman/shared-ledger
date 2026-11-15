import { useState } from "react";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { apiErrorMessage } from "@/api/client";
import {
  useCreatePriceAlert,
  useUpdatePriceAlert,
  type AlertExpiryPreset,
  type HoldingSummary,
  type PriceAlert,
} from "@/api/portfolio";
import { Button, FieldError, Input, Label } from "@/components/ui/primitives";
import { isoToday } from "@/lib/dates";
import { formatFullPrecisionMoney, formatPrice, formatPriceInput } from "@/lib/money";
import { applyPctShortcut, describeCadence, inferAlertDirection, isoDateInZone } from "./priceAlerts";

const PCT_SHORTCUTS = [-20, -10, -5, 5, 10, 20];

interface Props {
  householdId: string;
  holdingRow: HoldingSummary;
  displayTimezone?: string;
  existing?: PriceAlert | null;
  onClose: () => void;
}

export function PriceAlertModal({ householdId, holdingRow, displayTimezone, existing, onClose }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const create = useCreatePriceAlert(householdId);
  const update = useUpdatePriceAlert(householdId);

  const holding = holdingRow.holding;
  const currency = holdingRow.priceCurrency ?? holding.nativeCurrency;
  const currentPrice = holdingRow.currentPrice ?? existing?.currentPrice ?? null;
  const avgCost = holdingRow.averageCostPriceCurrency ?? null;

  const initialPreset: AlertExpiryPreset = existing
    ? existing.expiresAt == null
      ? "NO_EXPIRY"
      : "CUSTOM_DATE"
    : "DAYS_90";

  const [threshold, setThreshold] = useState(existing?.threshold ?? "");
  const [customPct, setCustomPct] = useState("");
  const [expiryPreset, setExpiryPreset] = useState<AlertExpiryPreset>(initialPreset);
  const [customDate, setCustomDate] = useState(
    existing?.expiresAt ? isoDateInZone(existing.expiresAt, displayTimezone) : isoToday(),
  );
  const [note, setNote] = useState(existing?.note ?? "");
  const [error, setError] = useState<string | null>(null);

  const inferred = inferAlertDirection(threshold, currentPrice);
  const equalOrInvalid = inferred === "EQUAL" || inferred === "INVALID";
  const avgCostInput = avgCost != null ? formatPriceInput(avgCost, currentPrice) : null;
  const costEqualsCurrent = (() => {
    if (!avgCostInput || !currentPrice) return false;
    try {
      return new Decimal(avgCostInput).eq(currentPrice) || (avgCost != null && new Decimal(avgCost).eq(currentPrice));
    } catch {
      return false;
    }
  })();

  const pending = create.isPending || update.isPending;

  async function save() {
    setError(null);
    if (!threshold.trim() || inferred == null || equalOrInvalid) {
      if (inferred === "EQUAL") {
        setError(t("portfolio.alerts.direction_equal_error"));
      } else {
        setError(t("errors.PRICE_ALERT_THRESHOLD_INVALID"));
      }
      return;
    }
    const payload = {
      threshold: threshold.trim(),
      expectedDirection: inferred,
      expiryPreset,
      customExpiryDate: expiryPreset === "CUSTOM_DATE" ? customDate : null,
      note: note.trim() ? note.trim() : null,
    };
    try {
      if (existing) {
        await update.mutateAsync({ alertId: existing.id, input: payload });
      } else {
        await create.mutateAsync({ holdingId: holding.id, ...payload });
      }
      onClose();
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  }

  function applyCustomPct() {
    const pct = Number(customPct);
    if (!Number.isFinite(pct) || pct === 0) return;
    const next = applyPctShortcut(currentPrice, pct);
    if (next) setThreshold(next);
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-black/40 px-4 py-8"
      role="dialog"
      aria-modal="true"
      aria-labelledby="price-alert-modal-title"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-lg border border-border bg-overlay p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between gap-2">
          <div>
            <h2 id="price-alert-modal-title" className="text-base font-semibold">
              {t(existing ? "portfolio.alerts.modal_title_edit" : "portfolio.alerts.modal_title_create", {
                symbol: holding.symbol,
              })}
            </h2>
            {holding.label && (
              <p className="text-xs text-gray-500 dark:text-gray-400">{holding.label}</p>
            )}
          </div>
          <Button variant="ghost" aria-label={t("common.cancel")} onClick={onClose}>✕</Button>
        </div>

        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 rounded-md bg-sunken p-3 text-xs">
            <div>
              <p className="text-gray-500 dark:text-gray-400">{t("portfolio.alerts.current_price")}</p>
              <p
                className="mt-0.5 font-mono text-sm font-medium tabular-nums"
                title={currentPrice != null ? formatFullPrecisionMoney(currentPrice, currency, locale) : undefined}
              >
                {currentPrice != null ? formatPrice(currentPrice, currency, locale) : "—"}
              </p>
            </div>
            <div>
              <p className="text-gray-500 dark:text-gray-400">{t("portfolio.alerts.avg_cost")}</p>
              <p
                className="mt-0.5 font-mono text-sm font-medium tabular-nums"
                title={avgCost != null ? formatFullPrecisionMoney(avgCost, currency, locale) : undefined}
              >
                {avgCost != null ? formatPrice(avgCost, currency, locale, currentPrice) : "—"}
              </p>
            </div>
          </div>

          <div>
            <Label htmlFor="alert-target-price">
              {t("portfolio.alerts.target_price", { currency })}
            </Label>
            <Input
              id="alert-target-price"
              type="number"
              step="any"
              min="0"
              value={threshold}
              invalid={inferred === "EQUAL" || inferred === "INVALID"}
              onChange={(e) => {
                setThreshold(e.target.value);
                setError(null);
              }}
              placeholder={currentPrice != null ? formatPriceInput(currentPrice) : ""}
            />
            {inferred === "ABOVE" && (
              <p className="mt-1 text-xs text-emerald-700 dark:text-emerald-400">
                {t("portfolio.alerts.direction_above", {
                  symbol: holding.symbol,
                  target: formatPrice(threshold, currency, locale),
                })}
              </p>
            )}
            {inferred === "BELOW" && (
              <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
                {t("portfolio.alerts.direction_below", {
                  symbol: holding.symbol,
                  target: formatPrice(threshold, currency, locale),
                })}
              </p>
            )}
            {inferred === "EQUAL" && (
              <p className="mt-1 text-xs text-red-600 dark:text-red-400">
                {t("portfolio.alerts.direction_equal_error")}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium text-gray-600 dark:text-gray-300">
              {t("portfolio.alerts.shortcuts")}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {avgCostInput != null && (
                <Button
                  type="button"
                  variant="secondary"
                  className="px-2 py-1 text-xs"
                  disabled={costEqualsCurrent}
                  title={costEqualsCurrent ? t("portfolio.alerts.shortcut_cost_at_current") : undefined}
                  onClick={() => setThreshold(avgCostInput)}
                >
                  {t("portfolio.alerts.shortcut_cost")}
                </Button>
              )}
              {PCT_SHORTCUTS.map((pct) => (
                <Button
                  key={pct}
                  type="button"
                  variant="secondary"
                  className="px-2 py-1 text-xs font-mono"
                  disabled={currentPrice == null}
                  onClick={() => {
                    const next = applyPctShortcut(currentPrice, pct);
                    if (next) setThreshold(next);
                  }}
                >
                  {pct > 0 ? `+${pct}%` : `${pct}%`}
                </Button>
              ))}
            </div>
            <div className="flex items-center gap-2">
              <Input
                type="number"
                step="any"
                className="w-32 text-xs"
                placeholder={t("portfolio.alerts.shortcut_custom_pct")}
                value={customPct}
                aria-label={t("portfolio.alerts.shortcut_custom_pct")}
                onChange={(e) => setCustomPct(e.target.value)}
              />
              <Button
                type="button"
                variant="secondary"
                className="px-2.5 py-1 text-xs"
                disabled={currentPrice == null || !customPct.trim() || Number(customPct) === 0}
                onClick={applyCustomPct}
              >
                {t("portfolio.alerts.shortcut_apply")}
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <Label>{t("portfolio.alerts.expiry")}</Label>
            <div className="grid grid-cols-2 gap-2">
              {(["DAYS_30", "DAYS_90", "CUSTOM_DATE", "NO_EXPIRY"] as AlertExpiryPreset[]).map((preset) => {
                const labelKey =
                  preset === "DAYS_30"
                    ? "portfolio.alerts.expiry_30d"
                    : preset === "DAYS_90"
                      ? "portfolio.alerts.expiry_90d"
                      : preset === "CUSTOM_DATE"
                        ? "portfolio.alerts.expiry_custom"
                        : "portfolio.alerts.expiry_none";
                const active = expiryPreset === preset;
                return (
                  <button
                    key={preset}
                    type="button"
                    aria-pressed={active}
                    onClick={() => setExpiryPreset(preset)}
                    className={`rounded-md border px-3 py-1.5 text-left text-xs font-medium transition-colors ${
                      active
                        ? "border-sky-600 bg-sky-50 text-sky-800 dark:border-sky-400 dark:bg-sky-900/30 dark:text-sky-200"
                        : "border-border text-gray-700 hover:bg-gray-50 dark:text-gray-300 dark:hover:bg-raised"
                    }`}
                  >
                    {t(labelKey)}
                  </button>
                );
              })}
            </div>
            {expiryPreset === "CUSTOM_DATE" && (
              <div className="space-y-1">
                <Input
                  type="date"
                  min={isoToday()}
                  value={customDate}
                  aria-label={t("portfolio.alerts.expiry_custom")}
                  onChange={(e) => setCustomDate(e.target.value)}
                />
                {displayTimezone && (
                  <p className="text-[11px] text-gray-500 dark:text-gray-400">
                    {t("portfolio.alerts.custom_date_help", { zone: displayTimezone })}
                  </p>
                )}
              </div>
            )}
          </div>

          <div>
            <Label htmlFor="alert-note">{t("portfolio.alerts.note")}</Label>
            <Input
              id="alert-note"
              maxLength={200}
              value={note}
              placeholder={t("portfolio.alerts.note_placeholder")}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>

          <div className="rounded-md border border-border bg-sunken px-3 py-2 text-xs text-gray-600 dark:text-gray-300">
            <p>{describeCadence(holdingRow.refreshCadence, t)}</p>
            <p className="mt-1 text-gray-500 dark:text-gray-400">{t("portfolio.alerts.one_shot_hint")}</p>
          </div>

          <FieldError message={error} />

          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={pending || currentPrice == null || !threshold.trim() || equalOrInvalid}
              onClick={() => void save()}
            >
              {t("portfolio.alerts.save")}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
