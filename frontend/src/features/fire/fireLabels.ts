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

export function scenarioMetrics(s: { meanPercent: string; stdDevPercent: string; historical: boolean }, t: TFunction): string {
  const base = `${s.meanPercent}% ±${s.stdDevPercent}%`;
  return s.historical ? `${base} ${t("fire.historical_ref")}` : base;
}

export function scenarioDisplayName(s: { name?: string }, idx: number, t: TFunction): string {
  if (s.name && s.name.trim().length > 0) return s.name.trim();
  if (idx === 0) return t("fire.scenario_default_1");
  if (idx === 1) return t("fire.scenario_default_2");
  if (idx === 2) return t("fire.scenario_default_3");
  return t("fire.scenario_default_n", { n: idx + 1 });
}

export function scenarioLabel(
  s: { name?: string; meanPercent: string; stdDevPercent: string; historical: boolean },
  t: TFunction,
): string {
  const name = s.name?.trim();
  const metrics = scenarioMetrics(s, t);
  return name ? `${name} (${metrics})` : metrics;
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

/** P(hit) as an integer percent, but never a misleading "0%": strictly positive yet rounding below 1%
 *  reads as "<1%" so it agrees with a non-dash median year. */
export function formatHitProbability(p: number): string {
  if (p <= 0) return "0%";
  const pct = p * 100;
  if (pct < 0.5) return "<1%";
  return `${Math.round(pct)}%`;
}

/** Median hit year telling the same story as {@link formatHitProbability}: shown only when some paths
 *  reached the target, "—" when none did. */
export function hitMedianYear(p: number, median: number | null): number | "—" {
  return p > 0 && median != null ? median : "—";
}

export type SeedPortfolioKind = "bonds" | "balanced" | "equities";

export interface SeedScenarioSpec {
  meanPercent: string;
  stdDevPercent: string;
  defaultNameKey: "fire.scenario_default_1" | "fire.scenario_default_2" | "fire.scenario_default_3";
  portfolio: SeedPortfolioKind;
}

/** Nominal arithmetic annual mean & std dev seeds matching FireDefaults.DEFAULT_RETURN_SCENARIOS. */
export const SEED_RETURN_SCENARIOS: readonly SeedScenarioSpec[] = [
  { meanPercent: "4.0", stdDevPercent: "5.0", defaultNameKey: "fire.scenario_default_1", portfolio: "bonds" },
  { meanPercent: "6.5", stdDevPercent: "10.0", defaultNameKey: "fire.scenario_default_2", portfolio: "balanced" },
  { meanPercent: "8.5", stdDevPercent: "15.0", defaultNameKey: "fire.scenario_default_3", portfolio: "equities" },
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
