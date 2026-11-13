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
