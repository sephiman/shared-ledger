import { useTranslation } from "react-i18next";
import type { FireProjection } from "@/api/fire";
import { Card, CardBody } from "@/components/ui/primitives";
import { formatMoney, formatPercent } from "@/lib/money";
import { formatHitProbability } from "./fireLabels";

/** Three-metric headline strip + plain-language bottom-line takeaway. */
export function FireHeroStrip({ projection, currency }: { projection: FireProjection; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);

  const nonHist = projection.scenarios.filter((s) => !s.historical);
  const pool = nonHist.length > 0 ? nonHist : projection.scenarios;
  const refScenario = pool[Math.floor(pool.length / 2)];
  const primaryStat =
    refScenario?.tierStats.find((st) => st.tier === "fire") ?? refScenario?.tierStats[0] ?? null;
  const projectedYear = primaryStat?.medianYearReachingTarget ?? primaryStat?.deterministicHitYear ?? null;
  const yearsAway = projectedYear !== null ? Math.max(0, projectedYear - projection.startYear) : null;
  const primaryTierLabel = primaryStat ? t(`fire.tier_${primaryStat.tier}`) : t("fire.tier_fire");
  const activeMonthlyStr = money(projection.contributions.activeMonthly ?? "0");
  const hitPct = primaryStat ? formatHitProbability(primaryStat.probabilityOfReachingTarget) : null;
  const survivalPct =
    primaryStat?.drawdownSurvivalRate != null ? formatHitProbability(primaryStat.drawdownSurvivalRate) : null;

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Card>
          <CardBody>
            <p className="text-xs text-gray-500 dark:text-gray-400">{t("fire.assumptions_wealth_label")}</p>
            <p className="mt-1 text-xl font-semibold tabular-nums">{money(projection.startingValue)}</p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {projection.snapshotDate ?? t("fire.no_snapshot_short")}
            </p>
          </CardBody>
        </Card>
        <Card>
          <CardBody>
            <p className="text-xs text-gray-500 dark:text-gray-400">{t("fire.savings_rate_label")}</p>
            <p className="mt-1 text-xl font-semibold tabular-nums">
              {projection.contributions.savingsRatePct !== null
                ? formatPercent(projection.contributions.savingsRatePct, locale, 1)
                : activeMonthlyStr}
            </p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.hero_contribution_sub", {
                amount: activeMonthlyStr,
              })}
            </p>
          </CardBody>
        </Card>
        <Card>
          <CardBody>
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {t("fire.hero_projected_tier", {
                tier: primaryTierLabel,
              })}
            </p>
            <p className="mt-1 text-xl font-semibold tabular-nums">
              {projectedYear !== null ? projectedYear : "—"}
            </p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {yearsAway !== null
                ? t("fire.hero_years_away", { years: yearsAway, mean: refScenario?.meanPercent ?? "—" })
                : t("fire.hero_beyond_horizon")}
            </p>
          </CardBody>
        </Card>
      </div>

      {primaryStat && hitPct && (
        <div className="rounded-lg border border-border bg-raised/60 px-4 py-2.5 text-sm text-gray-700 dark:text-gray-200">
          <span className="font-semibold text-gray-900 dark:text-gray-100">{t("fire.bottom_line_label")} </span>
          {projectedYear !== null && yearsAway !== null
            ? survivalPct !== null
              ? t("fire.bottom_line_full", {
                  monthly: activeMonthlyStr,
                  tier: primaryTierLabel,
                  year: projectedYear,
                  years: yearsAway,
                  hitPct,
                  survivalPct,
                  drawdownYears: projection.settings.drawdownYears,
                })
              : t("fire.bottom_line_no_survival", {
                  monthly: activeMonthlyStr,
                  tier: primaryTierLabel,
                  year: projectedYear,
                  years: yearsAway,
                  hitPct,
                })
            : t("fire.bottom_line_beyond", {
                monthly: activeMonthlyStr,
                tier: primaryTierLabel,
                hitPct,
              })}
        </div>
      )}
    </div>
  );
}
