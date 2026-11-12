import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { FireProjection, FireTierOutput } from "@/api/fire";
import { Badge, Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, formatPercent } from "@/lib/money";
import { Explain } from "./Explain";
import { TIER_COLORS, TIER_ORDER } from "./fireLabels";

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

/** One box per active tier: target today, the spending base it derives from, current coverage, and the
 *  instantiated formula behind it. */
export function FireTiersCard({ projection, currency }: { projection: FireProjection; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);

  const tiers = TIER_ORDER
    .map((key) => projection.tiers.find((tier) => tier.key === key))
    .filter((tier): tier is FireTierOutput => Boolean(tier?.enabled));

  const s = projection.settings;
  const taxOn = s.applyCapitalGainsTax;
  // The "<12 months of data" note only concerns tiers whose base is actually derived from data.
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
  const savingsRatePct = projection.contributions.savingsRatePct;

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

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">{t("fire.tiers")}</p>
          {savingsRatePct !== null && (
            <Badge tone={savingsRatePct >= 0 ? "green" : "amber"}>
              {t("fire.savings_rate_badge", { rate: formatPercent(savingsRatePct, locale, 1) })}
            </Badge>
          )}
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
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
          {tiers.map((tier) => (
            <TierBox key={tier.key} tier={tier} projection={projection} currency={currency} />
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
        {taxOn && (
          <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
            {t("fire.tax_targets_note", { gain: formatPercent(Number(projection.gainFraction.percent), locale, 2) })}
          </p>
        )}
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
          {t("fire.coverage_source_note", { value: money(projection.startingValue), date: projection.snapshotDate ?? "—" })}
        </p>
      </CardBody>
    </Card>
  );
}

function TierBox({ tier, projection, currency }: { tier: FireTierOutput; projection: FireProjection; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);
  const s = projection.settings;
  const taxOn = s.applyCapitalGainsTax;
  // Lean derives from the essential base; FIRE and Fat from the total base.
  const manualBase =
    tier.key === "lean"
      ? projection.spending.essentialMode === "manual"
      : tier.key === "fire" || tier.key === "fat"
        ? projection.spending.totalMode === "manual"
        : false;
  const variant = manualBase ? "_manual" : "";

  const baseLine = (() => {
    switch (tier.key) {
      case "lean":
        return t(`fire.tier_lean_base${variant}`, { monthly: money(tier.monthlyNetSpending) });
      case "fire":
        return t(`fire.tier_fire_base${variant}`, { monthly: money(tier.monthlyNetSpending) });
      case "fat":
        return t(`fire.tier_fat_base${variant}`, { monthly: money(projection.spending.totalMonthly), multiplier: s.fatMultiplier });
      case "custom":
        return t("fire.tier_custom_base");
    }
  })();

  const explain = (() => {
    if (tier.targetToday === null) return null;
    const params = {
      annual: money(taxOn ? tier.annualGrossSpending : tier.annualNetSpending),
      swr: s.safeWithdrawalRatePct,
      target: money(tier.targetToday),
      multiplier: s.fatMultiplier,
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

  const nonHist = projection.scenarios.filter((sc) => !sc.historical);
  const scenarioPool = nonHist.length > 0 ? nonHist : projection.scenarios;
  const refScenario = scenarioPool[Math.floor(scenarioPool.length / 2)];
  const coastTarget = refScenario?.tierStats.find((st) => st.tier === tier.key)?.coastTargetToday ?? null;
  const coastFundedPct =
    coastTarget && Number(coastTarget) > 0
      ? (Number(projection.startingValue) / Number(coastTarget)) * 100
      : null;

  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex items-center gap-2">
        <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: TIER_COLORS[tier.key] }} />
        <p className="font-medium">{t(`fire.tier_${tier.key}`)}</p>
      </div>
      {tier.targetToday === null ? (
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
          {tier.key === "custom" ? t("fire.no_target") : t("fire.no_spending_data")}
        </p>
      ) : (
        <>
          <p className="mt-1 text-xl font-semibold tabular-nums">{money(tier.targetToday)}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">{baseLine}</p>
          {tier.coveragePercent !== null && (
            <div className="mt-2.5">
              <div className="flex items-center justify-between text-xs text-gray-600 dark:text-gray-300">
                <span>{t("fire.coverage")}</span>
                <span className="font-medium tabular-nums">{formatPercent(tier.coveragePercent, locale, 0)}</span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded bg-gray-100 dark:bg-raised">
                <div
                  className="h-full rounded"
                  style={{ width: `${Math.min(100, Math.max(0, tier.coveragePercent))}%`, backgroundColor: TIER_COLORS[tier.key] }}
                />
              </div>
            </div>
          )}
          {((coastTarget !== null && coastFundedPct !== null && refScenario) ||
            (taxOn && tier.estimatedAnnualTax !== null && Number(tier.estimatedAnnualTax) > 0)) && (
            <dl className="mt-2.5 space-y-1 border-t border-border/60 pt-2 text-xs">
              {coastTarget !== null && coastFundedPct !== null && refScenario && (
                <div className="flex items-center justify-between gap-2">
                  <dt className="text-gray-500 dark:text-gray-400">
                    {t("fire.coast_short_label", { mean: refScenario.meanPercent })}
                  </dt>
                  <dd className="font-medium tabular-nums text-gray-700 dark:text-gray-200">
                    {money(coastTarget)} · {formatPercent(coastFundedPct, locale, 0)}
                  </dd>
                </div>
              )}
              {taxOn && tier.estimatedAnnualTax !== null && Number(tier.estimatedAnnualTax) > 0 && (
                <div className="flex items-center justify-between gap-2">
                  <dt className="text-gray-500 dark:text-gray-400">{t("fire.est_tax_label")}</dt>
                  <dd className="font-medium tabular-nums text-gray-700 dark:text-gray-200">
                    +{money(tier.estimatedAnnualTax)}/yr (
                    {formatPercent(
                      (Number(tier.estimatedAnnualTax) / Math.max(1e-9, Number(tier.annualGrossSpending))) * 100,
                      locale,
                      1,
                    )}
                    )
                  </dd>
                </div>
              )}
            </dl>
          )}
          {explain && (
            <Explain>
              <p>{explain}</p>
              {tier.coveragePercent !== null && (
                <p>
                  {t("fire.coverage_explain", {
                    wealth: money(projection.startingValue),
                    target: money(tier.targetToday),
                  })}
                </p>
              )}
            </Explain>
          )}
        </>
      )}
    </div>
  );
}
