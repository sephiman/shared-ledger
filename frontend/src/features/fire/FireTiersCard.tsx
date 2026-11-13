import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { FireProjection, FireTierOutput } from "@/api/fire";
import { Badge, Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatPercent, formatWholeMoney } from "@/lib/money";
import { formatDeltaValue } from "./FireHeroStrip";
import {
  TIER_COLORS,
  TIER_ORDER,
  estimatedYearText,
  formatHitProbability,
  formatMultiplier,
  resolveReferenceScenario,
  resolveReferenceTierKey,
  tierPlainName,
  tierTechName,
} from "./fireLabels";

const STALE_SNAPSHOT_DAYS = 45;

function readDismissed(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

function writeDismissed(key: string): void {
  try {
    window.localStorage.setItem(key, "1");
  } catch {
    // ignore storage quota / private-mode errors
  }
}

/** Single-row tier strip: plain-language labels, target amount, coverage bar, and reference-scenario year/probability,
 *  with coast, tax, and calculation formula tucked into a collapsed disclosure per tier. */
export function FireTiersCard({
  projection,
  baselineProjection,
  currency,
}: {
  projection: FireProjection;
  baselineProjection?: FireProjection;
  currency: string;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatWholeMoney(v ?? 0, currency, locale);

  const tiers = TIER_ORDER
    .map((key) => projection.tiers.find((tier) => tier.key === key))
    .filter((tier): tier is FireTierOutput => Boolean(tier?.enabled));

  const s = projection.settings;
  const taxOn = s.applyCapitalGainsTax;
  const refTierKey = resolveReferenceTierKey(projection);
  const anyDerivedBase = tiers.some((tier) =>
    tier.key === "lean"
      ? projection.spending.essentialMode === "derived"
      : tier.key === "fire" || tier.key === "fat"
        ? projection.spending.totalMode === "derived"
        : false,
  );
  const spendingIncoherent =
    Number(projection.spending.totalMonthly) > 0 &&
    Number(projection.spending.essentialMonthly) > Number(projection.spending.totalMonthly);

  const snapshotKey = `fire-dismissed:snapshot:${projection.snapshotDate ?? "none"}`;
  const gainKey = `fire-dismissed:gain:${projection.gainFraction.uncoveredMonths}`;
  const [staleDismissed, setStaleDismissed] = useState(() => readDismissed(snapshotKey));
  const [gainDismissed, setGainDismissed] = useState(() => readDismissed(gainKey));

  const snapshotAgeDays = projection.snapshotDate
    ? Math.floor((Date.now() - Date.parse(`${projection.snapshotDate}T00:00:00Z`)) / 86_400_000)
    : null;
  const staleSnapshot = !staleDismissed && (snapshotAgeDays === null || snapshotAgeDays > STALE_SNAPSHOT_DAYS);
  const gainUncovered =
    !gainDismissed &&
    taxOn &&
    projection.gainFraction.source === "movements" &&
    projection.gainFraction.uncoveredMonths > 0;

  if (tiers.length === 0 || tiers.every((tier) => tier.targetToday === null)) {
    return (
      <Card>
        <CardHeader>
          <p className="font-medium">{t("fire.tiers")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.no_tiers_active")}</p>
        </CardBody>
      </Card>
    );
  }

  const gridColsClass = tiers.length >= 4 ? "xl:grid-cols-4" : tiers.length === 2 ? "md:grid-cols-2" : "md:grid-cols-3";

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">{t("fire.tiers")}</p>
          <Link to="/fire?tab=settings" className="text-xs font-medium text-sky-600 hover:underline dark:text-sky-400">
            {t("fire.adjust_spending_link")}
          </Link>
        </div>
        {anyDerivedBase && projection.spending.monthsAvailable > 0 && projection.spending.monthsAvailable < 12 && (
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {t("fire.tier_months_note", { months: projection.spending.monthsAvailable })}
          </p>
        )}
      </CardHeader>
      <CardBody>
        {staleSnapshot && (
          <div className="mb-3 flex items-start justify-between gap-2 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
            <p>
              {snapshotAgeDays === null
                ? t("fire.no_snapshot_warning")
                : t("fire.stale_snapshot_warning", { date: projection.snapshotDate, days: snapshotAgeDays })}{" "}
              <Link to="/networth" className="font-medium underline">
                {t("fire.record_snapshot_link")}
              </Link>
            </p>
            <button
              type="button"
              aria-label={t("common.close")}
              onClick={() => {
                writeDismissed(snapshotKey);
                setStaleDismissed(true);
              }}
              className="shrink-0 rounded px-1 text-amber-800/70 hover:bg-amber-200/50 hover:text-amber-900 dark:text-amber-200/70 dark:hover:bg-amber-800/50 dark:hover:text-amber-100"
            >
              ×
            </button>
          </div>
        )}

        <div className={`grid grid-cols-1 gap-3 ${gridColsClass}`}>
          {tiers.map((tier) => (
            <TierBox
              key={tier.key}
              tier={tier}
              baseTier={baselineProjection?.tiers.find((b) => b.key === tier.key)}
              projection={projection}
              baselineProjection={baselineProjection}
              isReference={tier.key === refTierKey}
              currency={currency}
            />
          ))}
        </div>

        {spendingIncoherent && (
          <p className="mt-3 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
            {t("fire.spending_coherence_warning")}
          </p>
        )}
        {gainUncovered && (
          <div className="mt-3 flex items-start justify-between gap-2 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
            <p>
              {t("fire.gain_fraction_coverage_warning", {
                gain: formatPercent(Number(projection.gainFraction.percent), locale, 2),
                months: projection.gainFraction.uncoveredMonths,
              })}
            </p>
            <button
              type="button"
              aria-label={t("common.close")}
              onClick={() => {
                writeDismissed(gainKey);
                setGainDismissed(true);
              }}
              className="shrink-0 rounded px-1 text-amber-800/70 hover:bg-amber-200/50 hover:text-amber-900 dark:text-amber-200/70 dark:hover:bg-amber-800/50 dark:hover:text-amber-100"
            >
              ×
            </button>
          </div>
        )}

        <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
          {taxOn
            ? t("fire.tier_footnote_tax", {
                gain: formatPercent(Number(projection.gainFraction.percent), locale, 2),
                value: money(projection.startingValue),
                date: projection.snapshotDate ?? "—",
              })
            : t("fire.tier_footnote_no_tax", {
                value: money(projection.startingValue),
                date: projection.snapshotDate ?? "—",
              })}
        </p>
      </CardBody>
    </Card>
  );
}

function TierBox({
  tier,
  baseTier,
  projection,
  baselineProjection,
  isReference,
  currency,
}: {
  tier: FireTierOutput;
  baseTier?: FireTierOutput;
  projection: FireProjection;
  baselineProjection?: FireProjection;
  isReference: boolean;
  currency: string;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatWholeMoney(v ?? 0, currency, locale);
  const s = projection.settings;
  const taxOn = s.applyCapitalGainsTax;

  const plainLabel = tierPlainName(tier.key, s.fatMultiplier, locale, t);
  const techLabel = tierTechName(tier.key, t);

  const manualBase =
    tier.key === "lean"
      ? projection.spending.essentialMode === "manual"
      : tier.key === "fire" || tier.key === "fat"
        ? projection.spending.totalMode === "manual"
        : false;
  const variant = manualBase ? "_manual" : "";

  const explain = (() => {
    if (tier.targetToday === null) return null;
    const params = {
      annual: money(taxOn ? tier.annualGrossSpending : tier.annualNetSpending),
      swr: formatPercent(Number(s.safeWithdrawalRatePct), locale, 2),
      target: money(tier.targetToday),
      multiplier: formatMultiplier(s.fatMultiplier, locale),
    };
    switch (tier.key) {
      case "lean":
        return t(`fire.tier_lean_explain${variant}`, params);
      case "fire":
        return t(`fire.tier_fire_explain${variant}`, params);
      case "fat":
        return t(`fire.tier_fat_explain${variant}`, params);
      case "custom":
        return t("fire.tier_custom_explain");
    }
  })();

  const { scenario: refScenario } = resolveReferenceScenario(projection);
  const refStat = refScenario?.tierStats.find((st) => st.tier === tier.key);
  const baseScenario = baselineProjection ? resolveReferenceScenario(baselineProjection).scenario : undefined;
  const baseStat = baseScenario?.tierStats.find((st) => st.tier === tier.key);

  const hitYear = refStat ? estimatedYearText(refStat.estimatedYear, s.targetYear, t) : "—";
  const baseHitYear =
    baseStat && baselineProjection
      ? estimatedYearText(baseStat.estimatedYear, baselineProjection.settings.targetYear, t)
      : null;
  const hitProb = refStat ? formatHitProbability(refStat.probabilityOfReachingTarget) : "—";
  const baseHitProb = baseStat ? formatHitProbability(baseStat.probabilityOfReachingTarget) : null;

  const coastTarget = refStat?.coastTargetToday ?? null;
  const coastFundedPct =
    coastTarget && Number(coastTarget) > 0
      ? (Number(projection.startingValue) / Number(coastTarget)) * 100
      : null;

  const targetText = tier.targetToday !== null ? money(tier.targetToday) : "—";
  const baseTargetText = baseTier?.targetToday != null ? money(baseTier.targetToday) : null;
  const covText = tier.coveragePercent !== null ? formatPercent(tier.coveragePercent, locale, 0) : "—";
  const baseCovText = baseTier?.coveragePercent != null ? formatPercent(baseTier.coveragePercent, locale, 0) : null;

  return (
    <div
      className={`rounded-md border p-3 ${
        isReference
          ? "border-primary/70 bg-sky-50/30 ring-1 ring-primary/20 dark:bg-sky-950/20"
          : "border-border"
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: TIER_COLORS[tier.key] }} />
          <div>
            <p className="text-sm font-medium text-gray-900 dark:text-gray-100">{plainLabel}</p>
            {techLabel && <p className="text-xs text-gray-500 dark:text-gray-400">{techLabel}</p>}
          </div>
        </div>
        {isReference && <Badge tone="sky">{t("fire.tier_reference_badge")}</Badge>}
      </div>

      {tier.targetToday === null ? (
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
          {tier.key === "custom" ? t("fire.no_target") : t("fire.no_spending_data")}
        </p>
      ) : (
        <>
          <p className="mt-2 text-xl font-semibold tabular-nums">
            {formatDeltaValue(baseTargetText, targetText)}
          </p>

          {tier.coveragePercent !== null && (
            <div className="mt-2.5">
              <div className="flex items-center justify-between text-xs text-gray-600 dark:text-gray-300">
                <span>{t("fire.photo_coverage_tech")}</span>
                <span className="font-medium tabular-nums">{formatDeltaValue(baseCovText, covText)}</span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded bg-gray-100 dark:bg-raised">
                <div
                  className="h-full rounded"
                  style={{ width: `${Math.min(100, Math.max(0, tier.coveragePercent))}%`, backgroundColor: TIER_COLORS[tier.key] }}
                />
              </div>
            </div>
          )}

          {refStat && (
            <div className="mt-2.5 flex items-center justify-between text-xs text-gray-700 dark:text-gray-200">
              <span>{t("fire.photo_year_main")}</span>
              <span className="font-medium tabular-nums">
                {formatDeltaValue(baseHitYear, hitYear)}
                {" · "}
                {formatDeltaValue(baseHitProb, hitProb)}
              </span>
            </div>
          )}

          {/* Collapsed detail per tier: coast, estimated tax, and "How is this calculated?" */}
          <details className="mt-2.5 border-t border-border/60 pt-2 text-xs text-gray-500 dark:text-gray-400">
            <summary className="cursor-pointer select-none text-sky-600 hover:underline dark:text-sky-400">
              {t("fire.tier_details_toggle")}
            </summary>
            <div className="mt-2 space-y-2">
              {coastTarget !== null && coastFundedPct !== null && (
                <div>
                  <p className="text-gray-700 dark:text-gray-200">{t("fire.tier_coast_plain")}</p>
                  <p className="tabular-nums">
                    {t("fire.tier_coast_tech")}: {money(coastTarget)} · {formatPercent(coastFundedPct, locale, 0)}
                  </p>
                </div>
              )}
              {taxOn && tier.estimatedAnnualTax !== null && Number(tier.estimatedAnnualTax) > 0 && (
                <div className="flex items-center justify-between gap-2">
                  <span>{t("fire.est_tax_label")}</span>
                  <span className="font-medium tabular-nums text-gray-700 dark:text-gray-200">
                    +{money(tier.estimatedAnnualTax)}/yr (
                    {formatPercent(
                      (Number(tier.estimatedAnnualTax) / Math.max(1e-9, Number(tier.annualGrossSpending))) * 100,
                      locale,
                      1,
                    )}
                    )
                  </span>
                </div>
              )}
              {explain && (
                <div className="space-y-1">
                  <p className="font-medium text-gray-700 dark:text-gray-200">
                    {t("fire.tier_explain_question", { target: targetText })}
                  </p>
                  <p>{explain}</p>
                  {tier.coveragePercent !== null && (
                    <p>
                      {t("fire.coverage_explain", {
                        wealth: money(projection.startingValue),
                        target: targetText,
                      })}
                    </p>
                  )}
                </div>
              )}
            </div>
          </details>
        </>
      )}
    </div>
  );
}
