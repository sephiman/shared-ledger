import type { FireTierKey } from "@/api/fire";
import type { TFunction } from "i18next";

/** Dashed target-curve colors, one per tier, distinct from the scenario palette. */
export const TIER_COLORS: Record<FireTierKey, string> = {
  lean: "#059669",
  fire: "#d97706",
  fat: "#7c3aed",
  custom: "#475569",
};

export const TIER_ORDER: FireTierKey[] = ["lean", "fire", "fat", "custom"];

export function scenarioLabel(s: { meanPercent: string; stdDevPercent: string; historical: boolean }, t: TFunction): string {
  const base = `${s.meanPercent}% ±${s.stdDevPercent}%`;
  return s.historical ? `${base} ${t("fire.historical_ref")}` : base;
}

/**
 * P(hit) as an integer percent, but never a misleading "0%": a probability that is strictly
 * positive yet rounds below 1% reads as "<1%" so it agrees with a non-dash median year.
 */
export function formatHitProbability(p: number): string {
  if (p <= 0) return "0%";
  const pct = p * 100;
  if (pct < 0.5) return "<1%";
  return `${Math.round(pct)}%`;
}

/**
 * Median hit year that tells the same story as {@link formatHitProbability}: shown only when
 * some paths reached the target; "—" when none did (so "0%" never pairs with a year).
 */
export function hitMedianYear(p: number, median: number | null): number | "—" {
  return p > 0 && median != null ? median : "—";
}
