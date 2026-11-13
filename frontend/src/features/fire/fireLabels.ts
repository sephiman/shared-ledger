import type { FireProjection, FireScenarioOutput, FireTierKey, FireTierOutput } from "@/api/fire";
import type { TFunction } from "i18next";

/** Dashed target-curve colors, one per tier, distinct from the scenario palette. */
export const TIER_COLORS: Record<FireTierKey, string> = {
  lean: "#059669",
  fire: "#d97706",
  fat: "#7c3aed",
  custom: "#475569",
};

export const TIER_ORDER: FireTierKey[] = ["lean", "fire", "fat", "custom"];

export function scenarioMetrics(
  s: { meanPercent: string; stdDevPercent: string; historical: boolean },
  locale: string,
  t: TFunction,
): string {
  const base = `${formatFirePercent(Number(s.meanPercent), locale, 1)} ±${formatFirePercent(Number(s.stdDevPercent), locale, 1)}`;
  return s.historical ? `${base} ${t("fire.historical_ref")}` : base;
}

/** The localized default name of an unnamed scenario: Conservative/Base/Optimistic by ascending mean (ties by
 *  position), "Scenario N" from the fourth. Same ranking as the backend's reference-scenario fallback. */
export function defaultScenarioName(scenarios: readonly { meanPercent: string }[], idx: number, t: TFunction): string {
  const rank = scenarios
    .map((s, i) => ({ mean: Number(s.meanPercent), i }))
    .sort((a, b) => a.mean - b.mean || a.i - b.i)
    .findIndex((s) => s.i === idx) + 1;
  if (rank >= 1 && rank <= 3) return t(`fire.scenario_default_${rank}`);
  return t("fire.scenario_default_n", { n: rank });
}

/** A scenario's own name when the household gave one, otherwise its localized default. */
export function scenarioDisplayName(
  scenarios: readonly { name?: string; meanPercent: string }[],
  idx: number,
  t: TFunction,
): string {
  const own = scenarios[idx]?.name?.trim();
  return own ? own : defaultScenarioName(scenarios, idx, t);
}

/** The Fat FIRE multiplier as people write it: locale decimal separator, no trailing zeros ("1.50" → "1,5" in es). */
export function formatMultiplier(value: string | number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(Number(value));
}

export function tierPlainName(key: FireTierKey, fatMultiplier: string, locale: string, t: TFunction): string {
  return key === "fat"
    ? t("fire.tier_plain_fat", { multiplier: formatMultiplier(fatMultiplier, locale) })
    : t(`fire.tier_plain_${key}`);
}

/** Technical tier name shown as a secondary label; null for the own target, whose plain name is its only name. */
export function tierTechName(key: FireTierKey, t: TFunction): string | null {
  return key === "custom" ? null : t(`fire.tier_${key}`);
}

export function resolveReferenceScenarioIndex(projection: FireProjection): number {
  const idx = projection.settings.referenceScenarioIndex;
  if (idx >= 0 && idx < projection.scenarios.length) return idx;
  const nonHist = projection.scenarios
    .map((s, i) => ({ s, i }))
    .filter(({ s }) => !s.historical);
  const pool = nonHist.length > 0 ? nonHist : projection.scenarios.map((s, i) => ({ s, i }));
  return pool[Math.floor(pool.length / 2)]?.i ?? 0;
}

export function resolveReferenceScenario(projection: FireProjection): {
  scenario: FireScenarioOutput | undefined;
  index: number;
} {
  const index = resolveReferenceScenarioIndex(projection);
  return { scenario: projection.scenarios[index], index };
}

export function resolveReferenceTierKey(projection: FireProjection): FireTierKey {
  const wanted = projection.settings.referenceTier;
  const enabledTiers = projection.tiers.filter((tier) => tier.enabled);
  if (enabledTiers.some((tier) => tier.key === wanted)) return wanted;
  if (enabledTiers.some((tier) => tier.key === "fire")) return "fire";
  return enabledTiers[0]?.key ?? "fire";
}

export function resolveReferenceTier(projection: FireProjection): FireTierOutput | undefined {
  const key = resolveReferenceTierKey(projection);
  return projection.tiers.find((tier) => tier.key === key);
}

/** The FIRE page's one percentage format, `value` already scaled (65 → "65%"): the locale's own spacing ("65%" in
 *  English, "65 %" in Spanish), so two styles never meet in one sentence. A plain space, as elsewhere in the app. */
export function formatFirePercent(value: number, locale: string, fractionDigits = 0): string {
  return new Intl.NumberFormat(locale, {
    style: "percent",
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })
    .format(value / 100)
    .replace(/[\u00a0\u202f]/g, " ");
}

/** P(hit) as an integer percent, but never a misleading "0%": strictly positive yet rounding below 1%
 *  reads as "<1%" so it agrees with a non-dash median year. */
export function formatHitProbability(p: number, locale: string): string {
  if (p <= 0) return formatFirePercent(0, locale);
  const pct = p * 100;
  if (pct < 0.5) return `<${formatFirePercent(1, locale)}`;
  return formatFirePercent(Math.round(pct), locale);
}

/** The estimated year, or "beyond {horizon}" when fewer than half of all trials reach the target in time. */
export function estimatedYearText(year: number | null, horizon: number, t: TFunction): string {
  return year !== null ? String(year) : t("fire.year_beyond", { horizon });
}

export type SeedPortfolioKind = "bonds" | "balanced" | "equities";

export interface SeedScenarioSpec {
  meanPercent: string;
  stdDevPercent: string;
  portfolio: SeedPortfolioKind;
}

/** Nominal arithmetic annual mean & std dev seeds matching FireDefaults.DEFAULT_RETURN_SCENARIOS. */
export const SEED_RETURN_SCENARIOS: readonly SeedScenarioSpec[] = [
  { meanPercent: "4.0", stdDevPercent: "5.0", portfolio: "bonds" },
  { meanPercent: "6.5", stdDevPercent: "10.0", portfolio: "balanced" },
  { meanPercent: "8.5", stdDevPercent: "15.0", portfolio: "equities" },
];

function samePair(
  a: { meanPercent: string; stdDevPercent: string },
  b: { meanPercent: string; stdDevPercent: string },
): boolean {
  return (
    Math.abs(Number(a.meanPercent) - Number(b.meanPercent)) < 1e-6 &&
    Math.abs(Number(a.stdDevPercent) - Number(b.stdDevPercent)) < 1e-6
  );
}

/** True only when the complete scenario set equals the current seeds (same count, same values in order). */
export function isDefaultScenarioSet(
  scenarios: readonly { meanPercent: string; stdDevPercent: string; historical?: boolean }[],
): boolean {
  if (scenarios.length !== SEED_RETURN_SCENARIOS.length) return false;
  return SEED_RETURN_SCENARIOS.every((seed, idx) => !scenarios[idx].historical && samePair(scenarios[idx], seed));
}

export function findMatchingSeedPortfolio(
  scenario: { meanPercent: string; stdDevPercent: string; historical?: boolean },
): SeedPortfolioKind | null {
  if (scenario.historical) return null;
  return SEED_RETURN_SCENARIOS.find((seed) => samePair(scenario, seed))?.portfolio ?? null;
}

/** Exact typical compound (median geometric) annual return % = (exp(mu_ln) - 1) * 100, matching FireDefaults.typicalCompoundReturn. */
export function typicalCompoundReturnPct(meanPercent: number, stdDevPercent: number): number {
  const mean = meanPercent / 100;
  const stdDev = stdDevPercent / 100;
  if (stdDev === 0 || 1 + mean <= 0) return Math.max(mean, -0.9999) * 100;
  const onePlusMean = 1 + mean;
  const varianceRatio = (stdDev / onePlusMean) ** 2;
  const sigmaLn = Math.sqrt(Math.log(1 + varianceRatio));
  const muLn = Math.log(onePlusMean) - 0.5 * sigmaLn * sigmaLn;
  return (Math.exp(muLn) - 1) * 100;
}

export function realEquivalentPct(nominalCompoundPct: number, inflationPct: number): number {
  const r = nominalCompoundPct / 100;
  const i = inflationPct / 100;
  return ((1 + r) / (1 + i) - 1) * 100;
}
