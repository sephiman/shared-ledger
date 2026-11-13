import type { FireSettings } from "@/api/fire";

export type FireSettingsSection = "plan" | "spending" | "scenarios" | "drawdownFat" | "qualifying" | "tax" | "events";

const SECTION_FIELDS: Record<FireSettingsSection, readonly (keyof FireSettings)[]> = {
  plan: [
    "targetYear", "safeWithdrawalRatePct", "expectedInflationPct", "referenceTier", "referenceScenarioIndex",
    "tierLeanEnabled", "tierFireEnabled", "tierFatEnabled", "tierCustomEnabled", "targetAmount",
    "contributionMode", "monthlyContribution", "indexContribution",
  ],
  spending: [
    "spendingLookbackMonths", "spendingAggregation", "excludeCurrentMonth",
    "essentialSpendingMode", "manualEssentialSpending", "totalSpendingMode", "manualTotalSpending",
  ],
  scenarios: ["returnScenarios"],
  drawdownFat: ["drawdownYears", "fatMultiplier"],
  qualifying: ["qualifyingAssetClasses"],
  tax: ["applyCapitalGainsTax", "indexTaxBrackets", "fallbackGainFractionPct", "taxBrackets"],
  events: ["cashFlowEvents"],
};

/** The settings sections whose fields differ from what is saved. */
export function unsavedSections(form: FireSettings, saved: FireSettings | undefined): ReadonlySet<FireSettingsSection> {
  if (!saved) return new Set();
  const differs = (field: keyof FireSettings) => JSON.stringify(form[field]) !== JSON.stringify(saved[field]);
  return new Set(
    (Object.keys(SECTION_FIELDS) as FireSettingsSection[]).filter((section) => SECTION_FIELDS[section].some(differs)),
  );
}
