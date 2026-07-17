import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useFireProjection, useFireSettings, useUpdateFireSettings, type FireSettings } from "@/api/fire";
import { useAssetClasses } from "@/api/catalog";
import { apiErrorMessage } from "@/api/client";
import { FireChart } from "./FireChart";
import { FireTiersCard } from "./FireTiersCard";
import { FireScenarioTable } from "./FireScenarioTable";
import { FireSettingsCard, type FireFormErrors } from "./FireSettingsCard";

export function FirePage() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const isOwner = household.role === "owner";
  const { data: settings } = useFireSettings(household.householdId);
  const { data: projection } = useFireProjection(household.householdId);
  const { data: assetClasses = [] } = useAssetClasses();
  const update = useUpdateFireSettings(household.householdId);

  const [form, setForm] = useState<FireSettings | null>(null);
  const [errors, setErrors] = useState<FireFormErrors>({});
  useEffect(() => {
    if (settings) setForm(settings);
  }, [settings]);

  function onChange(next: FireSettings, clear?: (keyof FireFormErrors)[]) {
    setForm(next);
    if (update.isSuccess || update.isError) update.reset();
    if (clear?.length) {
      setErrors((prev) => {
        const cleaned = { ...prev };
        for (const key of clear) delete cleaned[key];
        return cleaned;
      });
    }
  }

  function onScenarioErrorClear(idx: number, field: "meanPercent" | "stdDevPercent") {
    setErrors((prev) => {
      if (!prev.scenarios?.[idx]?.[field]) return prev;
      const scenarios = [...(prev.scenarios ?? [])];
      scenarios[idx] = { ...scenarios[idx], [field]: undefined };
      return { ...prev, scenarios };
    });
  }

  function validateAndSave() {
    if (!form) return;
    const next: FireFormErrors = {};
    const isNum = (v: string) => v.trim() !== "" && Number.isFinite(Number(v));

    if (form.tierCustomEnabled && (!isNum(form.targetAmount) || Number(form.targetAmount) <= 0)) {
      next.targetAmount = t("errors.amount_positive");
    }
    if (!Number.isFinite(form.targetYear) || form.targetYear <= 0) next.targetYear = t("errors.number_required");
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

    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }
    setErrors({});
    update.mutate(form);
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">{t("fire.title")}</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("fire.description")}</p>
      </div>

      {projection && <FireTiersCard projection={projection} currency={household.currency} />}
      {projection && <FireChart projection={projection} currency={household.currency} />}
      {projection && <FireScenarioTable projection={projection} />}

      {form && (
        <FireSettingsCard
          form={form}
          errors={errors}
          isOwner={isOwner}
          saving={update.isPending}
          saved={update.isSuccess}
          serverError={update.isError ? apiErrorMessage(update.error, t) : null}
          projection={projection}
          assetClasses={assetClasses}
          currency={household.currency}
          onChange={onChange}
          onScenarioErrorClear={onScenarioErrorClear}
          onSave={validateAndSave}
        />
      )}
    </div>
  );
}
