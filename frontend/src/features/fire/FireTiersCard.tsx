import { useTranslation } from "react-i18next";
import type { FireProjection, FireTierOutput } from "@/api/fire";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, formatPercent } from "@/lib/money";
import { Explain } from "./Explain";
import { TIER_COLORS, TIER_ORDER } from "./fireLabels";

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
        <p className="font-medium">{t("fire.tiers")}</p>
        {anyDerivedBase && projection.spending.monthsAvailable > 0 && projection.spending.monthsAvailable < 12 && (
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            {t("fire.tier_months_note", { months: projection.spending.monthsAvailable })}
          </p>
        )}
      </CardHeader>
      <CardBody>
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

  return (
    <div className="rounded-md border border-border p-3 dark:border-gray-700">
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
          <p className="mt-1 text-xl font-semibold">{money(tier.targetToday)}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">{baseLine}</p>
          {taxOn && tier.estimatedAnnualTax !== null && Number(tier.estimatedAnnualTax) > 0 && (
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.tier_tax_note", {
                net: money(tier.annualNetSpending),
                gross: money(tier.annualGrossSpending),
                tax: money(tier.estimatedAnnualTax),
                rate: formatPercent(
                  (Number(tier.estimatedAnnualTax) / Math.max(1e-9, Number(tier.annualGrossSpending))) * 100,
                  locale,
                  1,
                ),
              })}
            </p>
          )}
          {tier.coveragePercent !== null && (
            <div className="mt-2">
              <div className="flex items-center justify-between text-xs text-gray-600 dark:text-gray-300">
                <span>{t("fire.coverage")}</span>
                <span className="font-medium">{formatPercent(tier.coveragePercent, locale, 0)}</span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded bg-gray-100 dark:bg-gray-700">
                <div
                  className="h-full rounded"
                  style={{ width: `${Math.min(100, Math.max(0, tier.coveragePercent))}%`, backgroundColor: TIER_COLORS[tier.key] }}
                />
              </div>
            </div>
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
