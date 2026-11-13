import type { TFunction } from "i18next";
import type { FireProjection } from "@/api/fire";
import { formatWholeMoney } from "@/lib/money";
import {
  formatHitProbability,
  resolveReferenceScenario,
  resolveReferenceTier,
  scenarioDisplayName,
  formatFirePercent,
} from "./fireLabels";

export const HEADLINE_TEMPLATE_VERSION = 2;
// The question buttons are the first two levers of "What moves your year most"; keep in step with FireLeverValues.
export const WHAT_IF_SAVE_MORE_AMOUNT = 100;
export const WHAT_IF_SPEND_LESS_PCT = 5;

export function buildCoverageClause(
  projection: FireProjection,
  locale: string,
  t: TFunction,
): string | null {
  const tier = resolveReferenceTier(projection);
  if (!tier || tier.coveragePercent === null) return null;
  const pct = formatFirePercent(tier.coveragePercent, locale, 0);
  return t("fire.headline_coverage_clause", { pct });
}

export function buildProjectionClause(
  projection: FireProjection,
  currency: string,
  locale: string,
  t: TFunction,
): string {
  const tier = resolveReferenceTier(projection);
  const { scenario, index } = resolveReferenceScenario(projection);
  const stat = scenario && tier ? scenario.tierStats.find((st) => st.tier === tier.key) : undefined;

  if (!tier || tier.targetToday === null || !scenario || !stat) {
    return t("fire.headline_no_target");
  }

  const monthly = formatWholeMoney(projection.contributions.activeMonthly ?? "0", currency, locale);
  const scenarioName = scenarioDisplayName(projection.scenarios, index, t);
  const hitPct = formatHitProbability(stat.probabilityOfReachingTarget, locale);
  const year = stat.estimatedYear;

  if (year === null) {
    const notBefore = t("fire.headline_not_before_horizon", {
      monthly,
      scenario: scenarioName,
      horizon: projection.settings.targetYear,
      hitPct,
    });
    const extra = extraMonthlyNeeded(projection, stat.requiredMonthlyToHitTarget);
    if (extra === null) return notBefore;
    return `${notBefore} ${t("fire.headline_extra_monthly", { amount: formatWholeMoney(extra, currency, locale) })}`;
  }

  const reach = t("fire.headline_reach", {
    monthly,
    scenario: scenarioName,
    year,
    years: Math.max(0, year - projection.startYear),
    hitPct,
  });
  if (stat.drawdownSurvivalRate === null) return reach;
  const survival = t("fire.headline_survival_if_reached", {
    survivalPct: formatHitProbability(stat.drawdownSurvivalRate, locale),
    drawdownYears: projection.settings.drawdownYears,
  });
  return `${reach} ${survival}`;
}

/** Extra monthly saving on top of today's that would reach the target in time; null when unknown or not needed. */
function extraMonthlyNeeded(projection: FireProjection, requiredMonthly: string | null): number | null {
  if (requiredMonthly === null) return null;
  const extra = Number(requiredMonthly) - Number(projection.contributions.activeMonthly ?? 0);
  return Math.round(extra) > 0 ? extra : null;
}

export function buildHeadline(
  projection: FireProjection,
  currency: string,
  locale: string,
  t: TFunction,
): string {
  const coverage = buildCoverageClause(projection, locale, t);
  const proj = buildProjectionClause(projection, currency, locale, t);
  return coverage ? `${coverage} ${proj}` : proj;
}
