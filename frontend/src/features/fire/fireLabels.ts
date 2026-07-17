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
