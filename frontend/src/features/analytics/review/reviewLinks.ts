import type { TFunction } from "i18next";
import type { ExplorerLink, TransactionLinkFilter, VerifiedMetric } from "@/api/analyticsAi";
import type { AiCitationItem } from "@/components/ai/AiCitations";
import { explorerSearch } from "@/features/analytics/explorerUrl";
import { withTransactionFilters } from "@/features/transactions/txFilters";
import { formatDayMonthYear } from "@/lib/dates";
import { formatNumber, formatPercent, formatWholeMoney } from "@/lib/money";
import { defaultRange } from "@/lib/range";

/** Builds a /transactions deep link with the exact same URL helper and key order as the manual filter bar. */
export function transactionsUrlFromFilter(filter: TransactionLinkFilter): string {
  const params = withTransactionFilters(new URLSearchParams(), {
    from: filter.from,
    to: filter.to,
    direction: filter.direction,
    categoryCode: filter.categoryCode ?? undefined,
    categoryGroup: filter.categoryGroup ?? undefined,
    q: filter.q ?? undefined,
  });
  const s = params.toString();
  return s ? `/transactions?${s}` : "/transactions";
}

/** Builds an /analytics/explorer deep link with the exact same URL helper as the manual Explorer controls. */
export function explorerUrlFromLink(link: ExplorerLink): string {
  const range =
    link.preset === "custom"
      ? { preset: "custom" as const, from: link.from ?? "", to: link.to ?? "" }
      : defaultRange(link.preset);
  const params = explorerSearch({
    scope: { type: link.scopeType, code: link.scopeCode },
    range,
  });
  const s = params.toString();
  return s ? `/analytics/explorer?${s}` : "/analytics/explorer";
}

const CITATION_LABELS: Record<string, string> = {
  income: "analytics.review.cite.income",
  expenses: "analytics.review.cite.expenses",
  savings: "analytics.review.cite.savings",
  savingsRatePct: "analytics.review.cite.savings_rate",
  avgIncome: "analytics.review.cite.avg_income",
  avgExpenses: "analytics.review.cite.avg_expenses",
  avgNetSavings: "analytics.review.cite.avg_savings",
  medianNetSavings: "analytics.review.cite.median_savings",
  expensesDeltaAbs: "analytics.review.cite.expenses_delta",
  expensesDeltaPct: "analytics.review.cite.expenses_delta_pct",
  savingsDeltaAbs: "analytics.review.cite.savings_delta",
  monthlyCost: "analytics.review.cite.monthly_cost",
  annualCost: "analytics.review.cite.annual_cost",
  priorYearAmount: "analytics.review.cite.prior_year",
  yoyDeltaAbs: "analytics.review.cite.yoy_delta",
  yoyDeltaPct: "analytics.review.cite.yoy_delta_pct",
  potentialMonthlySaving: "analytics.review.cite.potential_monthly",
  potentialAnnualSaving: "analytics.review.cite.potential_annual",
  total: "analytics.review.cite.total",
  total12m: "analytics.review.cite.total_12m",
  purchases: "analytics.review.cite.purchases",
  occurrences: "analytics.review.cite.occurrences",
  averageTicket: "analytics.review.cite.avg_ticket",
  shareOfCategoryPct: "analytics.review.cite.category_share",
  monthlyEquivalent: "analytics.review.cite.monthly_avg",
  monthlyAverage: "analytics.review.cite.monthly_avg",
  monthlyMean: "analytics.review.cite.monthly_mean",
  monthlyMedian: "analytics.review.cite.monthly_median",
  trimToMedianMonthly: "analytics.review.cite.trim_monthly",
  trimToMedianAnnual: "analytics.review.cite.trim_annual",
  aboveMedianMonthly: "analytics.review.cite.trim_monthly",
  aboveMedianAnnual: "analytics.review.cite.trim_annual",
  nonZeroMonths: "analytics.review.cite.active_months",
  peakMonthAmount: "analytics.review.cite.peak_month",
  periodAmount: "analytics.review.cite.period_amount",
  baselineAmount: "analytics.review.cite.comparison_amount",
  comparisonAmount: "analytics.review.cite.comparison_amount",
  deltaAbs: "analytics.review.cite.delta",
  deltaPct: "analytics.review.cite.delta_pct",
  periodPurchases: "analytics.review.cite.purchases",
  topDriverTotal: "analytics.review.cite.top_driver_total",
  topDriverSharePct: "analytics.review.cite.category_share",
  topDriverPurchases: "analytics.review.cite.top_driver_purchases",
  budget: "analytics.review.cite.budget",
  spent: "analytics.review.cite.spent",
  projected: "analytics.review.cite.projected",
  pctConsumed: "analytics.review.cite.pct_consumed",
  overrunMultiple: "analytics.review.cite.overrun_multiple",
  overUnder: "analytics.review.cite.over_under",
  essentialSharePct: "analytics.review.cite.essential_share",
  nonEssentialSharePct: "analytics.review.cite.non_essential_share",
  combinedMonthlySaving: "analytics.review.cite.combined_monthly",
  combinedAnnualSaving: "analytics.review.cite.combined_annual",
  combinedPotentialMonthly: "analytics.review.cite.combined_monthly",
  combinedPotentialAnnual: "analytics.review.cite.combined_annual",
  monthsAvailable: "analytics.review.cite.months_available",
  staleSpendingSince: "analytics.review.cite.stale_since",
};

const COUNT_LEAVES = new Set([
  "occurrences",
  "purchases",
  "nonZeroMonths",
  "monthsAvailable",
  "daysElapsed",
  "daysInMonth",
  "merchantCount",
  "budgetedCategoriesCount",
  "activeExpenseCategoriesCount",
]);

export function formatReviewMetricLabel(key: string, t: TFunction): string {
  const i18nKey = CITATION_LABELS[key];
  if (i18nKey) return t(i18nKey);
  return key.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase()).trim();
}

export function formatReviewCitationLabel(c: AiCitationItem, t: TFunction): string {
  const segments = c.path.replace(/\[(\d+)]/g, ".$1").split(".").filter(Boolean);
  const nonNumeric = segments.filter((s) => !/^\d+$/.test(s));
  const leaf = nonNumeric[nonNumeric.length - 1] ?? c.path;
  const leafLabel = formatReviewMetricLabel(leaf, t);
  if (!c.context) return leafLabel;
  const localizedCtx = c.context.includes(".")
    ? t(`categories.${c.context}`, { defaultValue: c.context })
    : t(`groups.${c.context}`, { defaultValue: c.context });
  return `${localizedCtx} · ${leafLabel}`;
}

/** Formats verified citation values through the app's own formatters (whole euros with grouping,
 *  locale percentages, day-month-year dates). */
export function formatReviewCitationValue(c: AiCitationItem, currency: string, locale: string): string {
  if (c.value == null) return "—";
  const leaf = c.path.split(".").pop() ?? "";
  if (typeof c.value === "boolean") return String(c.value);
  if (typeof c.value === "string") {
    if (/^\d{4}-\d{2}-\d{2}$/.test(c.value)) return formatDayMonthYear(c.value, locale);
    const num = Number(c.value);
    if (!Number.isFinite(num) || c.value.trim() === "") return c.value;
    return formatNumericByLeaf(leaf, num, currency, locale);
  }
  if (typeof c.value === "number" && Number.isFinite(c.value)) {
    return formatNumericByLeaf(leaf, c.value, currency, locale);
  }
  return JSON.stringify(c.value);
}

export function formatVerifiedMetricValue(metric: VerifiedMetric, currency: string, locale: string): string {
  const num = Number(metric.value);
  if (!Number.isFinite(num)) return metric.value;
  if (metric.kind === "percent") return formatPercent(num, locale, 1);
  if (metric.kind === "count") return formatNumber(num, locale, 0);
  if (metric.kind === "multiple") return `${formatNumber(num, locale, num % 1 === 0 ? 0 : 1)}×`;
  if (metric.kind === "money" || metric.kind === "whole_money") return formatWholeMoney(num, currency, locale);
  return metric.value;
}

function formatNumericByLeaf(leaf: string, num: number, currency: string, locale: string): string {
  if (leaf.endsWith("Pct") || leaf.endsWith("Percent") || leaf.endsWith("Share")) {
    return formatPercent(num, locale, 1);
  }
  if (COUNT_LEAVES.has(leaf)) {
    return formatNumber(num, locale, 0);
  }
  return formatWholeMoney(num, currency, locale);
}
