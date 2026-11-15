import type { TFunction } from "i18next";

const SEGMENT_KEYS: Record<string, string> = {
  scope: "fire.ai.cite.scope",
  headline: "fire.ai.cite.headline",
  startingValue: "fire.ai.cite.starting_value",
  targetYear: "fire.ai.cite.target_year",
  expectedInflationPct: "fire.ai.cite.inflation",
  safeWithdrawalRatePct: "fire.ai.cite.swr",
  drawdownYears: "fire.ai.cite.drawdown_years",
  targetToday: "fire.ai.cite.target_today",
  coveragePercent: "fire.ai.cite.coverage",
  monthlyNetSpending: "fire.ai.cite.monthly_net_spending",
  annualNetSpending: "fire.ai.cite.annual_net_spending",
  annualGrossSpending: "fire.ai.cite.annual_gross_spending",
  estimatedAnnualTax: "fire.ai.cite.estimated_tax",
  deterministicHitYear: "fire.ai.cite.deterministic_year",
  probabilityOfReachingTargetPct: "fire.ai.cite.hit_probability",
  estimatedYear: "fire.ai.cite.estimated_year",
  medianYearAmongReaching: "fire.ai.cite.median_reaching_year",
  coastProbabilityPct: "fire.ai.cite.coast_probability",
  coastEstimatedYear: "fire.ai.cite.coast_year",
  coastTargetToday: "fire.ai.cite.coast_target",
  requiredMonthlyToHitTarget: "fire.ai.cite.required_monthly",
  drawdownSurvivalRatePct: "fire.ai.cite.drawdown_survival",
  tiers: "fire.ai.cite.tiers",
  scenarios: "fire.ai.cite.scenarios",
  meanPercent: "fire.ai.cite.mean_return",
  stdDevPercent: "fire.ai.cite.volatility",
  horizonP10: "fire.ai.cite.horizon_p10",
  horizonP50: "fire.ai.cite.horizon_p50",
  horizonP90: "fire.ai.cite.horizon_p90",
  horizonNominalP10: "fire.ai.cite.horizon_p10",
  horizonNominalP50: "fire.ai.cite.horizon_p50",
  horizonNominalP90: "fire.ai.cite.horizon_p90",
  assumptionsVsReality: "fire.ai.cite.assumptions",
  spending: "fire.ai.cite.spending",
  essentialMonthly: "fire.ai.cite.essential_monthly",
  totalMonthly: "fire.ai.cite.total_monthly",
  derivedEssentialMonthly: "fire.ai.cite.derived_essential",
  derivedTotalMonthly: "fire.ai.cite.derived_total",
  monthsAvailable: "fire.ai.cite.months_available",
  noSpendingSince: "fire.ai.cite.no_spending_since",
  contributions: "fire.ai.cite.contributions",
  activeMonthly: "fire.ai.cite.active_monthly",
  manualMonthly: "fire.ai.cite.manual_monthly",
  savingsMonthly: "fire.ai.cite.savings_monthly",
  savingsRatePct: "fire.ai.cite.savings_rate",
  movementsMonthly: "fire.ai.cite.movements_monthly",
  returns: "fire.ai.cite.returns",
  referenceMeanPercent: "fire.ai.cite.ref_mean",
  suggestedFromAssetMix: "fire.ai.cite.asset_mix_return",
  householdHistorical: "fire.ai.cite.historical_return",
  actualXirr: "fire.ai.cite.actual_xirr",
  annualizedPercent: "fire.ai.cite.annualized_xirr",
  uncoveredMonths: "fire.ai.cite.uncovered_months",
  taxAndPrincipal: "fire.ai.cite.tax_principal",
  gainFractionPct: "fire.ai.cite.gain_fraction",
  history: "fire.ai.cite.history",
  attribution: "fire.ai.cite.attribution",
  totalPoints: "fire.ai.cite.total_points",
  contributedPoints: "fire.ai.cite.contributed_points",
  marketPoints: "fire.ai.cite.market_points",
  wealthPoints: "fire.ai.cite.wealth_points",
  needPoints: "fire.ai.cite.need_points",
  levers: "fire.ai.cite.levers",
  yearChange: "fire.ai.cite.year_change",
  probabilityChangePoints: "fire.ai.cite.probability_change",
};

/** Turns a dotted payload path + optional backend context into a concise human label for citation pills. */
export function formatFireCitationPath(path: string, t: TFunction, context?: string | null): string {
  const segments = path.replace(/\[(\d+)]/g, ".$1").split(".").filter(Boolean);
  const nonNumeric = segments.filter((s) => !/^\d+$/.test(s));
  const last = nonNumeric[nonNumeric.length - 1];
  const parent = nonNumeric.length >= 2 ? nonNumeric[nonNumeric.length - 2] : null;
  const leafLabel = last ? (SEGMENT_KEYS[last] ? t(SEGMENT_KEYS[last]) : humanize(last)) : path;
  if (context) return `${context} · ${leafLabel}`;
  if (parent && parent !== "headline" && parent !== "scope" && parent !== "assumptionsVsReality") {
    const parentLabel = SEGMENT_KEYS[parent] ? t(SEGMENT_KEYS[parent]) : humanize(parent);
    return `${parentLabel} · ${leafLabel}`;
  }
  return leafLabel;
}

function humanize(key: string): string {
  return key.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase()).trim();
}
