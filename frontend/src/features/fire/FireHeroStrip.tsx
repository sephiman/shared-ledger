import { useTranslation } from "react-i18next";
import type { FireProjection } from "@/api/fire";
import { Card, CardBody } from "@/components/ui/primitives";
import { formatMoney, formatPercent } from "@/lib/money";

/** Three-metric headline strip so the user sees wealth, savings rate, and projected FIRE year in one glance. */
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

  return (
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
              : money(projection.contributions.activeMonthly ?? "0")}
          </p>
          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
            {t("fire.hero_contribution_sub", {
              amount: money(projection.contributions.activeMonthly ?? "0"),
            })}
          </p>
        </CardBody>
      </Card>
      <Card>
        <CardBody>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {t("fire.hero_projected_tier", {
              tier: primaryStat ? t(`fire.tier_${primaryStat.tier}`) : t("fire.tier_fire"),
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
  );
}
