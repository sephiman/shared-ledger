import type { TFunction } from "i18next";
import type { FireProjection } from "@/api/fire";
import { formatMoney, formatPercent } from "@/lib/money";
import {
  formatHitProbability,
  resolveReferenceScenario,
  resolveReferenceTier,
  scenarioDisplayName,
} from "./fireLabels";

export const HEADLINE_TEMPLATE_VERSION = 1;
export const WHAT_IF_SAVE_MORE_AMOUNT = 500;
export const WHAT_IF_SPEND_LESS_FACTOR = 0.9;

export function buildCoverageClause(
  projection: FireProjection,
  locale: string,
  t: TFunction,
): string | null {
  const tier = resolveReferenceTier(projection);
  if (!tier || tier.coveragePercent === null) return null;
  const pct = formatPercent(tier.coveragePercent, locale, 0);
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

  const monthly = formatMoney(projection.contributions.activeMonthly ?? "0", currency, locale);
  const scenarioName = scenarioDisplayName(scenario, index, t);
  const hitPct = formatHitProbability(stat.probabilityOfReachingTarget);
  const projectedYear = stat.medianYearReachingTarget;

  if (projectedYear !== null && stat.probabilityOfReachingTarget > 0) {
    const years = Math.max(0, projectedYear - projection.startYear);
    const survivalPct =
      stat.drawdownSurvivalRate !== null ? formatHitProbability(stat.drawdownSurvivalRate) : null;
    if (survivalPct !== null) {
      return t("fire.headline_reach_full", {
        monthly,
        scenario: scenarioName,
        year: projectedYear,
        years,
        hitPct,
        survivalPct,
        drawdownYears: projection.settings.drawdownYears,
      });
    }
    return t("fire.headline_reach_no_survival", {
      monthly,
      scenario: scenarioName,
      year: projectedYear,
      years,
      hitPct,
    });
  }

  return t("fire.headline_beyond_horizon", {
    monthly,
    scenario: scenarioName,
    horizon: projection.settings.targetYear,
    hitPct,
  });
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
