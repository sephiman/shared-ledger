import type { TFunction } from "i18next";
import type { FireSettings } from "@/api/fire";
import { cashFlowEventProblems } from "./fireTemplates";

export interface FireFormErrors {
  targetAmount?: string;
  targetYear?: string;
  monthlyContribution?: string;
  qualifying?: string;
  swr?: string;
  inflation?: string;
  fatMultiplier?: string;
  gainFraction?: string;
  brackets?: string;
  manualEssential?: string;
  manualTotal?: string;
  drawdownYears?: string;
  cashFlowEvents?: string;
  scenarios?: Array<{ meanPercent?: string; stdDevPercent?: string }>;
}

const isNum = (v: string) => v.trim() !== "" && Number.isFinite(Number(v));

/** What Save would refuse; empty when the form can be saved (and therefore previewed). */
export function fireFormErrors(form: FireSettings, startYear: number | undefined, t: TFunction): FireFormErrors {
  const next: FireFormErrors = {};

  if (form.tierCustomEnabled && (!isNum(form.targetAmount) || Number(form.targetAmount) <= 0)) {
    next.targetAmount = t("errors.amount_positive");
  }
  if (!Number.isFinite(form.targetYear) || form.targetYear <= 0) next.targetYear = t("errors.number_required");
  if (!Number.isFinite(form.drawdownYears) || form.drawdownYears < 1 || form.drawdownYears > 80) {
    next.drawdownYears = t("errors.number_required");
  }
  if (form.cashFlowEvents.some((ev) => !Number.isInteger(ev.year) || ev.year <= 0 || !isNum(ev.amount))) {
    next.cashFlowEvents = t("errors.number_required");
  } else if (startYear !== undefined) {
    // Never save a one-off amount the projection would silently ignore.
    const notSimulated = cashFlowEventProblems(form.cashFlowEvents, startYear, form.targetYear)
      .find((p) => p.problem === "not_simulated");
    if (notSimulated) {
      next.cashFlowEvents = t("fire.event_lump_not_simulated", { year: notSimulated.firstYear - 1, firstYear: notSimulated.firstYear });
    }
  }
  if (!isNum(form.monthlyContribution) || Number(form.monthlyContribution) < 0) {
    next.monthlyContribution = t("errors.number_required");
  }
  if (!isNum(form.safeWithdrawalRatePct) || Number(form.safeWithdrawalRatePct) <= 0 || Number(form.safeWithdrawalRatePct) > 100) {
    next.swr = t("errors.number_required");
  }
  if (!isNum(form.expectedInflationPct) || Number(form.expectedInflationPct) < 0 || Number(form.expectedInflationPct) > 50) {
    next.inflation = t("errors.number_required");
  }
  if (!isNum(form.fatMultiplier) || Number(form.fatMultiplier) < 1 || Number(form.fatMultiplier) > 100) {
    next.fatMultiplier = t("errors.number_required");
  }
  if (!isNum(form.fallbackGainFractionPct) || Number(form.fallbackGainFractionPct) < 0 || Number(form.fallbackGainFractionPct) > 100) {
    next.gainFraction = t("errors.number_required");
  }
  if (form.qualifyingAssetClasses.length === 0) next.qualifying = t("errors.select_at_least_one");
  if (form.essentialSpendingMode === "manual" && (!isNum(form.manualEssentialSpending) || Number(form.manualEssentialSpending) <= 0)) {
    next.manualEssential = t("errors.amount_positive");
  }
  if (form.totalSpendingMode === "manual" && (!isNum(form.manualTotalSpending) || Number(form.manualTotalSpending) <= 0)) {
    next.manualTotal = t("errors.amount_positive");
  }

  const scenarioErrs: Array<{ meanPercent?: string; stdDevPercent?: string }> = [];
  let anyScenarioErr = false;
  form.returnScenarios.forEach((sc, idx) => {
    const row: { meanPercent?: string; stdDevPercent?: string } = {};
    if (!isNum(sc.meanPercent)) {
      row.meanPercent = t("errors.number_required");
      anyScenarioErr = true;
    }
    if (!isNum(sc.stdDevPercent) || Number(sc.stdDevPercent) < 0) {
      row.stdDevPercent = t("errors.number_required");
      anyScenarioErr = true;
    }
    scenarioErrs[idx] = row;
  });
  if (anyScenarioErr) next.scenarios = scenarioErrs;

  const bracketNumbersOk = form.taxBrackets.every(
    (b) => isNum(b.lowerBound) && Number(b.lowerBound) >= 0 && isNum(b.ratePct) && Number(b.ratePct) >= 0 && Number(b.ratePct) <= 100,
  );
  const bracketOrderOk = form.taxBrackets.every(
    (b, i) => i === 0 || Number(b.lowerBound) > Number(form.taxBrackets[i - 1].lowerBound),
  );
  if (form.taxBrackets.length === 0 || !bracketNumbersOk || !bracketOrderOk) {
    next.brackets = t("fire.tax_brackets_invalid");
  }
  return next;
}
