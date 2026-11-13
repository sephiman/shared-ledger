import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import type { CashFlowEventKind, FireProjection, FireSettings, FireTierKey, ReturnScenario } from "@/api/fire";
import { Badge, Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Toggle } from "@/components/ui/primitives";
import { formatNumber, formatWholeMoney } from "@/lib/money";
import { Explain } from "./Explain";
import {
  SEED_RETURN_SCENARIOS,
  TIER_ORDER,
  formatMultiplier,
  isDefaultScenarioSet,
  resolveReferenceScenario,
  resolveReferenceTierKey,
  defaultScenarioName,
  scenarioDisplayName,
  tierPlainName,
  tierTechName,
  formatFirePercent,
} from "./fireLabels";
import { ScenarioInfo } from "./ScenarioInfo";
import { FireEventTemplates } from "./FireEventTemplates";
import { cashFlowEventProblems, eventsSummary } from "./fireTemplates";
import { cn } from "@/lib/cn";

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

interface Props {
  householdId: string;
  form: FireSettings;
  errors: FireFormErrors;
  isOwner: boolean;
  saving: boolean;
  saved: boolean;
  serverError: string | null;
  projection: FireProjection | undefined;
  assetClasses: { code: string }[];
  currency: string;
  onChange: (next: FireSettings, clear?: (keyof FireFormErrors)[]) => void;
  onScenarioErrorClear: (idx: number, field: "meanPercent" | "stdDevPercent") => void;
  onSave: () => void;
}

function AdvancedSection({
  title,
  badge,
  summary,
  children,
}: {
  title: string;
  badge?: ReactNode;
  summary: string;
  children: ReactNode;
}) {
  return (
    <details className="rounded-md border border-border bg-surface">
      <summary className="flex cursor-pointer select-none flex-wrap items-center justify-between gap-2 px-3 py-2.5 text-sm font-medium text-gray-800 hover:bg-raised/60 dark:text-gray-200">
        <span className="flex items-center gap-2">
          <span>{title}</span>
          {badge}
        </span>
        <span className="text-xs font-normal text-gray-500 dark:text-gray-400">{summary}</span>
      </summary>
      <div className="border-t border-border/60 p-3">{children}</div>
    </details>
  );
}

/** Two-layer FIRE settings: Plan (always visible, plain questions) and Advanced (collapsed sections with live summaries). */
export function FireSettingsCard({
  householdId,
  form,
  errors,
  isOwner,
  saving,
  saved,
  serverError,
  projection,
  assetClasses,
  currency,
  onChange,
  onScenarioErrorClear,
  onSave,
}: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatWholeMoney(v ?? 0, currency, locale);
  // Help-text percentages keep their own precision: 4 → "4 %", 3.5 → "3,5 %", 4.25 → "4,25 %".
  const pct = (v: string | number) => {
    const n = Number(v);
    return formatFirePercent(n, locale, Number.isInteger(n) ? 0 : Number.isInteger(n * 10) ? 1 : 2);
  };
  const ro = !isOwner;
  const [confirmRestoreOpen, setConfirmRestoreOpen] = useState(false);

  const saved_ = projection?.settings;
  const fireTier = projection?.tiers.find((x) => x.key === "fire" && x.targetToday !== null);
  const fatTier = projection?.tiers.find((x) => x.key === "fat" && x.targetToday !== null);

  const swrEffect = (() => {
    if (!fireTier || !saved_) return t("fire.swr_effect_generic");
    const swr = Number(saved_.safeWithdrawalRatePct);
    const alt = swr > 1 ? swr - 0.5 : swr + 0.5;
    const altTarget = (Number(fireTier.targetToday) * swr) / alt;
    return t("fire.swr_effect", {
      swr: pct(saved_.safeWithdrawalRatePct),
      target: money(fireTier.targetToday),
      alt: pct(alt),
      altTarget: money(altTarget),
    });
  })();

  const inflationEffect = (() => {
    if (!fireTier || !saved_ || !projection) return t("fire.inflation_effect_generic");
    const n = Math.max(0, Number(saved_.targetYear) - projection.startYear);
    if (n <= 0) return t("fire.inflation_effect_generic");
    const curveLast = fireTier.targetCurve[fireTier.targetCurve.length - 1];
    const projected = curveLast
      ? Number(curveLast.value)
      : Number(fireTier.targetToday) * (1 + Number(saved_.expectedInflationPct) / 100) ** n;
    return t("fire.inflation_effect", { target: money(projected), year: saved_.targetYear });
  })();

  const fatEffect = fatTier && saved_
    ? t("fire.fat_multiplier_effect", { multiplier: formatMultiplier(saved_.fatMultiplier, locale), target: money(fatTier.targetToday) })
    : t("fire.fat_multiplier_effect_generic");

  const taxEffect = (() => {
    if (!saved_?.applyCapitalGainsTax || !fireTier || fireTier.estimatedAnnualTax === null) {
      return t("fire.tax_apply_effect_generic");
    }
    return t("fire.tax_apply_effect", {
      net: money(fireTier.annualNetSpending),
      gross: money(fireTier.annualGrossSpending),
      tax: money(fireTier.estimatedAnnualTax),
    });
  })();

  function patch(next: Partial<FireSettings>, clear?: (keyof FireFormErrors)[]) {
    onChange({ ...form, ...next }, clear);
  }

  function setScenarioField(idx: number, field: "name" | "meanPercent" | "stdDevPercent", value: string) {
    const next = form.returnScenarios.map((s, i) => (i === idx ? { ...s, [field]: value } : s));
    patch({ returnScenarios: next });
    if (field !== "name") onScenarioErrorClear(idx, field);
  }

  function addScenario(scenario: ReturnScenario) {
    patch({ returnScenarios: [...form.returnScenarios, scenario] });
  }

  const tierFlag: Record<FireTierKey, keyof FireSettings> = {
    lean: "tierLeanEnabled",
    fire: "tierFireEnabled",
    fat: "tierFatEnabled",
    custom: "tierCustomEnabled",
  };

  const enabledTierKeys = TIER_ORDER.filter((k) => Boolean(form[tierFlag[k]]));
  const tierOptionLabel = (key: FireTierKey) => {
    const tech = tierTechName(key, t);
    const plain = tierPlainName(key, form.fatMultiplier, locale, t);
    return tech ? `${plain} (${tech})` : plain;
  };

  // Advanced section summaries
  const effEssentialVal =
    form.essentialSpendingMode === "manual"
      ? form.manualEssentialSpending
      : projection?.spending.derivedEssentialMonthly ?? "0";
  const effTotalVal =
    form.totalSpendingMode === "manual"
      ? form.manualTotalSpending
      : projection?.spending.derivedTotalMonthly ?? "0";

  const spendingSummary = t("fire.settings_summary_spending", {
    lookback: form.spendingLookbackMonths,
    agg: t(`fire.spending_agg_short_${form.spendingAggregation}`),
    essential: `${money(effEssentialVal)}${form.essentialSpendingMode === "manual" ? ` (${t("fire.settings_manual_tag")})` : ""}`,
    total: `${money(effTotalVal)}${form.totalSpendingMode === "manual" ? ` (${t("fire.settings_manual_tag")})` : ""}`,
  });

  const scenariosSummary = `${form.returnScenarios.length} · ${form.returnScenarios
    .map((sc, idx) => `${scenarioDisplayName(form.returnScenarios, idx, t)} ${pct(sc.meanPercent)}`)
    .join(" · ")}`;

  const drawdownFatSummary = t("fire.settings_summary_drawdown_fat", {
    years: form.drawdownYears,
    multiplier: formatMultiplier(form.fatMultiplier, locale),
  });

  const qualifyingSummary = form.qualifyingAssetClasses
    .map((code) => t(`asset.${code}`))
    .join(", ");

  const minRate = form.taxBrackets.length > 0 ? Math.min(...form.taxBrackets.map((b) => Number(b.ratePct))) : 0;
  const maxRate = form.taxBrackets.length > 0 ? Math.max(...form.taxBrackets.map((b) => Number(b.ratePct))) : 0;
  const gainSourceLabel =
    projection?.gainFraction.source === "movements"
      ? t("fire.settings_summary_gain_derived")
      : t("fire.settings_summary_gain_manual");
  const gainPctStr = projection
    ? formatFirePercent(Number(projection.gainFraction.percent), locale, 0)
    : pct(form.fallbackGainFractionPct);
  const taxSummary = form.applyCapitalGainsTax
    ? t("fire.settings_summary_tax_on", {
        count: form.taxBrackets.length,
        minRate: formatNumber(minRate, locale, Number.isInteger(minRate) ? 0 : 1),
        maxRate: pct(maxRate),
        source: gainSourceLabel,
        gain: gainPctStr,
      })
    : t("fire.settings_summary_tax_off");

  const eventsSummaryText = eventsSummary(form.cashFlowEvents, t);
  const eventProblems = projection ? cashFlowEventProblems(form.cashFlowEvents, projection.startYear, form.targetYear) : [];

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("nav.settings")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("fire.settings_description")}</p>
        {ro && <p className="mt-1 text-sm text-amber-700 dark:text-amber-300">{t("fire.owners_only")}</p>}
      </CardHeader>
      <CardBody className="space-y-6">
        {/* Layer 1: Plan (always visible, plain-language questions) */}
        <section className="space-y-4">
          <p className="text-sm font-semibold text-gray-800 dark:text-gray-100">{t("fire.settings_plan_title")}</p>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <div>
              <Label>{t("fire.settings_horizon_q")}</Label>
              <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.settings_horizon_tech")}</p>
              <Input
                type="number"
                value={form.targetYear}
                disabled={ro}
                invalid={!!errors.targetYear}
                onChange={(e) => patch({ targetYear: Number(e.target.value) }, ["targetYear"])}
              />
              <FieldError message={errors.targetYear} />
              {(() => {
                const startYear = projection?.startYear ?? new Date().getFullYear();
                const yearsAway = form.targetYear - startYear;
                const refTierKey = projection ? resolveReferenceTierKey(projection) : form.referenceTier;
                const refScenario = projection ? resolveReferenceScenario(projection).scenario : undefined;
                const refStat = refScenario?.tierStats.find((st) => st.tier === refTierKey);
                const needsExtend = yearsAway < 10 || (refStat != null && refStat.estimatedYear === null);
                return (
                  <>
                    <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                      {yearsAway > 0 ? `${t("fire.target_year_distance", { count: yearsAway })} ` : ""}
                      {t("fire.target_year_effect")}
                    </p>
                    {needsExtend && (
                      <div className="mt-1.5 flex flex-wrap items-center gap-2 rounded-md bg-amber-50/80 px-2.5 py-1.5 text-xs text-amber-800 dark:bg-amber-900/30 dark:text-amber-200">
                        <span>{t("fire.horizon_extend_hint")}</span>
                        <Button
                          variant="secondary"
                          disabled={ro}
                          onClick={() => patch({ targetYear: form.targetYear + 10 }, ["targetYear"])}
                        >
                          {t("fire.horizon_plus_10")}
                        </Button>
                      </div>
                    )}
                  </>
                );
              })()}
            </div>

            <div>
              <Label>{t("fire.settings_swr_q")}</Label>
              <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.settings_swr_tech")}</p>
              <Input
                type="number"
                step="0.1"
                min="0.1"
                value={form.safeWithdrawalRatePct}
                disabled={ro}
                invalid={!!errors.swr}
                onChange={(e) => patch({ safeWithdrawalRatePct: e.target.value }, ["swr"])}
              />
              <FieldError message={errors.swr} />
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{swrEffect}</p>
            </div>

            <div>
              <Label>{t("fire.settings_inflation_q")}</Label>
              <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.settings_inflation_tech")}</p>
              <Input
                type="number"
                step="0.1"
                min="0"
                value={form.expectedInflationPct}
                disabled={ro}
                invalid={!!errors.inflation}
                onChange={(e) => patch({ expectedInflationPct: e.target.value }, ["inflation"])}
              />
              <FieldError message={errors.inflation} />
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{inflationEffect}</p>
            </div>

            <div>
              <Label>{t("fire.settings_reference_tier_q")}</Label>
              <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.settings_reference_tier_tech")}</p>
              <Select
                value={form.referenceTier}
                disabled={ro}
                onChange={(e) => patch({ referenceTier: e.target.value as FireTierKey })}
              >
                {enabledTierKeys.map((tk) => (
                  <option key={tk} value={tk}>
                    {tierOptionLabel(tk)}
                  </option>
                ))}
              </Select>
            </div>

            <div>
              <Label>{t("fire.settings_reference_scenario_q")}</Label>
              <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.settings_reference_scenario_tech")}</p>
              <Select
                value={String(form.referenceScenarioIndex)}
                disabled={ro}
                onChange={(e) => patch({ referenceScenarioIndex: Number(e.target.value) })}
              >
                {form.returnScenarios.map((sc, idx) => (
                  <option key={idx} value={String(idx)}>
                    {scenarioDisplayName(form.returnScenarios, idx, t)} ({pct(sc.meanPercent)} ±{pct(sc.stdDevPercent)})
                  </option>
                ))}
              </Select>
            </div>

            <div>
              <Label>{t("fire.settings_tiers_shown_q")}</Label>
              <div className="mt-1.5 flex flex-wrap gap-3">
                {TIER_ORDER.map((key) => (
                  <label key={key} className="flex items-center gap-1.5 text-sm">
                    <input
                      type="checkbox"
                      className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                      checked={Boolean(form[tierFlag[key]])}
                      disabled={ro}
                      onChange={(e) => {
                        const checked = e.target.checked;
                        const next: Partial<FireSettings> = { [tierFlag[key]]: checked } as Partial<FireSettings>;
                        if (!checked && form.referenceTier === key) {
                          next.referenceTier = "fire";
                        }
                        patch(next);
                      }}
                    />
                    <span>
                      {tierPlainName(key, form.fatMultiplier, locale, t)}
                      {tierTechName(key, t) && (
                        <span className="ml-1 text-xs text-gray-500 dark:text-gray-400">({tierTechName(key, t)})</span>
                      )}
                    </span>
                  </label>
                ))}
              </div>
              {form.tierCustomEnabled && (
                <div className="mt-2">
                  <Label className="text-xs">{t("fire.target_amount")}</Label>
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    value={form.targetAmount}
                    disabled={ro}
                    invalid={!!errors.targetAmount}
                    onChange={(e) => patch({ targetAmount: e.target.value }, ["targetAmount"])}
                  />
                  <FieldError message={errors.targetAmount} />
                </div>
              )}
            </div>
          </div>

          {/* Monthly contribution */}
          <div className="space-y-2">
            <Label>{t("fire.settings_contrib_q")}</Label>
            <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
              {(["savings", "movements", "manual"] as const).map((mode) => {
                const derived = projection?.contributions;
                const value =
                  mode === "manual" ? form.monthlyContribution
                  : mode === "savings" ? derived?.savingsMonthly ?? null
                  : derived?.movementsMonthly ?? null;
                return (
                  <div key={mode} className="rounded-md border border-border p-2.5">
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="radio"
                        name="fire-contribution-mode"
                        className="h-4 w-4 border-border text-primary focus:ring-primary"
                        checked={form.contributionMode === mode}
                        disabled={ro}
                        onChange={() => patch({ contributionMode: mode })}
                      />
                      <span className="flex-1 font-medium">{t(`fire.contribution_${mode}`)}</span>
                      {mode === "manual" ? (
                        <Input
                          type="number"
                          step="0.01"
                          className="w-28"
                          value={form.monthlyContribution}
                          disabled={ro}
                          invalid={!!errors.monthlyContribution}
                          onChange={(e) => patch({ monthlyContribution: e.target.value }, ["monthlyContribution"])}
                        />
                      ) : (
                        <span className="font-medium tabular-nums">
                          {value !== null ? money(value) : t("fire.contribution_no_data")}
                        </span>
                      )}
                    </label>
                    {mode !== "manual" && (
                      <p className="ml-6 mt-1 text-xs text-gray-500 dark:text-gray-400">
                        {t(`fire.contribution_${mode}_explain`)}
                      </p>
                    )}
                    {mode === "manual" && <FieldError message={errors.monthlyContribution} />}
                  </div>
                );
              })}
            </div>
            <div className="pt-1">
              <Toggle
                checked={form.indexContribution}
                disabled={ro}
                onChange={(v) => patch({ indexContribution: v })}
                label={t("fire.index_contribution")}
              />
            </div>
            {projection?.contributions.savingsMonthly != null && projection.contributions.movementsMonthly != null &&
              form.contributionMode !== "manual" && (
              <p className="text-xs text-amber-700 dark:text-amber-300">
                {t("fire.contribution_gap", {
                  savings: money(projection.contributions.savingsMonthly),
                  movements: money(projection.contributions.movementsMonthly),
                })}
              </p>
            )}
          </div>
        </section>

        {/* Layer 2: Advanced (collapsed sections in required order, each with a one-line summary) */}
        <section className="space-y-2">
          <p className="text-sm font-semibold text-gray-800 dark:text-gray-100">{t("fire.settings_advanced_title")}</p>

          {/* 1. Spending bases */}
          <AdvancedSection title={t("fire.spending_bases")} summary={spendingSummary}>
            <div className="space-y-3">
              <p className="text-xs text-gray-500 dark:text-gray-400">{t("fire.spending_bases_help")}</p>
              <div className="grid grid-cols-1 gap-3 rounded-md border border-border bg-raised/40 p-3 sm:grid-cols-3">
                <div>
                  <Label className="text-xs">{t("fire.spending_lookback_label")}</Label>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    {[3, 6, 12, 24, 36].map((m) => (
                      <button
                        key={m}
                        type="button"
                        disabled={ro}
                        onClick={() => patch({ spendingLookbackMonths: m })}
                        className={`rounded px-2 py-1 text-xs font-medium ${
                          form.spendingLookbackMonths === m
                            ? "bg-primary text-white"
                            : "border border-border bg-surface text-gray-700 hover:bg-raised dark:text-gray-200"
                        }`}
                      >
                        {m}m
                      </button>
                    ))}
                    <Input
                      type="number"
                      min={1}
                      max={120}
                      className="w-16"
                      value={form.spendingLookbackMonths}
                      disabled={ro}
                      onChange={(e) => {
                        const v = Number(e.target.value);
                        if (Number.isFinite(v)) patch({ spendingLookbackMonths: Math.max(1, Math.min(120, Math.round(v))) });
                      }}
                    />
                  </div>
                </div>
                <div>
                  <Label className="text-xs">{t("fire.spending_agg_label")}</Label>
                  <div className="mt-1 flex gap-1.5">
                    {(["mean", "median"] as const).map((agg) => (
                      <button
                        key={agg}
                        type="button"
                        disabled={ro}
                        onClick={() => patch({ spendingAggregation: agg })}
                        className={`rounded px-2.5 py-1 text-xs font-medium ${
                          form.spendingAggregation === agg
                            ? "bg-primary text-white"
                            : "border border-border bg-surface text-gray-700 hover:bg-raised dark:text-gray-200"
                        }`}
                      >
                        {t(`fire.spending_agg_${agg}`)}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="flex items-end pb-1">
                  <label className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-200">
                    <input
                      type="checkbox"
                      className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                      checked={form.excludeCurrentMonth}
                      disabled={ro}
                      onChange={(e) => patch({ excludeCurrentMonth: e.target.checked })}
                    />
                    {t("fire.exclude_current_month")}
                  </label>
                </div>
              </div>

              {(["essential", "total"] as const).map((base) => {
                const mode = base === "essential" ? form.essentialSpendingMode : form.totalSpendingMode;
                const manualValue = base === "essential" ? form.manualEssentialSpending : form.manualTotalSpending;
                const derived = base === "essential"
                  ? projection?.spending.derivedEssentialMonthly
                  : projection?.spending.derivedTotalMonthly;
                const hasDerivedData = (projection?.spending.monthsAvailable ?? 0) > 0;
                const modeField = base === "essential" ? "essentialSpendingMode" : "totalSpendingMode";
                const valueField = base === "essential" ? "manualEssentialSpending" : "manualTotalSpending";
                const errKey = (base === "essential" ? "manualEssential" : "manualTotal") as keyof FireFormErrors;
                const error = errors[errKey] as string | undefined;

                return (
                  <div key={base} className="rounded-md border border-border p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <span className="text-sm font-medium">{t(`fire.spending_${base}`)}</span>
                        <span className="ml-2 text-sm tabular-nums text-gray-700 dark:text-gray-200">
                          {hasDerivedData ? money(derived) : t("fire.contribution_no_data")}
                        </span>
                        {mode === "manual" && (
                          <Badge tone="amber" className="ml-2">{t("fire.settings_manual_tag")}</Badge>
                        )}
                      </div>
                      {!ro && (
                        mode === "derived" ? (
                          <button
                            type="button"
                            onClick={() =>
                              patch({
                                [modeField]: "manual",
                                [valueField]: derived && Number(derived) > 0 ? Number(derived).toFixed(2) : manualValue,
                              } as Partial<FireSettings>)
                            }
                            className="text-xs font-medium text-sky-600 hover:underline dark:text-sky-400"
                          >
                            {t("fire.settings_override_link")}
                          </button>
                        ) : (
                          <button
                            type="button"
                            onClick={() => patch({ [modeField]: "derived" } as Partial<FireSettings>, [errKey])}
                            className="text-xs font-medium text-sky-600 hover:underline dark:text-sky-400"
                          >
                            {t("fire.settings_use_derived_link")}
                          </button>
                        )
                      )}
                    </div>
                    <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                      {t(`fire.spending_${base}_derived_explain`, {
                        count: form.spendingLookbackMonths,
                        method: t(`fire.spending_method_${form.spendingAggregation}`),
                        window: t(form.excludeCurrentMonth ? "fire.spending_window_excludes_current" : "fire.spending_window_includes_current"),
                      })}
                    </p>
                    {mode === "manual" && (
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <Input
                          type="number"
                          step="0.01"
                          min="0"
                          className="w-36"
                          aria-label={t(`fire.spending_${base}`)}
                          value={manualValue}
                          disabled={ro}
                          invalid={!!error}
                          onChange={(e) => patch({ [valueField]: e.target.value } as Partial<FireSettings>, [errKey])}
                        />
                        <FieldError message={error} />
                        {hasDerivedData && (
                          <span className="text-xs text-amber-700 dark:text-amber-300">
                            {t(`fire.spending_${base}_gap`, { derived: money(derived), manual: money(manualValue) })}
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}

              {(() => {
                const effEssential = form.essentialSpendingMode === "manual"
                  ? Number(form.manualEssentialSpending)
                  : Number(projection?.spending.derivedEssentialMonthly ?? 0);
                const effTotal = form.totalSpendingMode === "manual"
                  ? Number(form.manualTotalSpending)
                  : Number(projection?.spending.derivedTotalMonthly ?? 0);
                const incoherent = Number.isFinite(effEssential) && Number.isFinite(effTotal) && effTotal > 0 && effEssential > effTotal;
                return incoherent ? (
                  <p className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
                    {t("fire.spending_coherence_warning")}
                  </p>
                ) : null;
              })()}
            </div>
          </AdvancedSection>

          {/* 2. Return scenarios (names editable here) */}
          <AdvancedSection
            title={t("fire.scenarios")}
            badge={isDefaultScenarioSet(form.returnScenarios) ? <Badge tone="neutral">{t("fire.scenarios_default_badge")}</Badge> : undefined}
            summary={scenariosSummary}
          >
            <div className="space-y-2">
              <p className="text-xs text-gray-600 dark:text-gray-300">
                {t("fire.scenarios_intro")}
              </p>
              <Explain>
                <p>{t("fire.scenarios_help")}</p>
              </Explain>
              {form.returnScenarios.map((sc, idx) => {
                const meanErr = errors.scenarios?.[idx]?.meanPercent;
                const stdErr = errors.scenarios?.[idx]?.stdDevPercent;
                const displayName = scenarioDisplayName(form.returnScenarios, idx, t);
                return (
                  <div key={idx} className="grid grid-cols-1 items-start gap-2 sm:grid-cols-12">
                    <div className="sm:col-span-4">
                      <div className="flex items-center gap-1.5">
                        <Label className="text-xs">{t("fire.scenario_name")}</Label>
                        <ScenarioInfo
                          name={displayName}
                          meanPercent={sc.meanPercent}
                          stdDevPercent={sc.stdDevPercent}
                          historical={sc.historical}
                          inflationPct={Number(form.expectedInflationPct)}
                        />
                      </div>
                      <Input
                        type="text"
                        value={sc.name}
                        disabled={ro}
                        placeholder={defaultScenarioName(form.returnScenarios, idx, t)}
                        onChange={(e) => setScenarioField(idx, "name", e.target.value)}
                      />
                    </div>
                    <div className="sm:col-span-3">
                      <Label className="text-xs">{t("fire.mean_return")}</Label>
                      <Input
                        type="number"
                        step="0.1"
                        value={sc.meanPercent}
                        disabled={ro}
                        invalid={!!meanErr}
                        onChange={(e) => setScenarioField(idx, "meanPercent", e.target.value)}
                      />
                      <FieldError message={meanErr} />
                    </div>
                    <div className="sm:col-span-3">
                      <Label className="text-xs">{t("fire.std_dev")}</Label>
                      <Input
                        type="number"
                        step="0.1"
                        min="0"
                        value={sc.stdDevPercent}
                        disabled={ro}
                        invalid={!!stdErr}
                        onChange={(e) => setScenarioField(idx, "stdDevPercent", e.target.value)}
                      />
                      <FieldError message={stdErr} />
                    </div>
                    <div className="flex items-center gap-2 sm:col-span-2 sm:mt-6">
                      {sc.historical && (
                        <Badge tone="sky" className="whitespace-nowrap">
                          <span title={t("fire.historical_tooltip", { years: projection?.historicalScenario?.yearsOfData ?? 0 })}>
                            {t("fire.historical_ref")}
                          </span>
                        </Badge>
                      )}
                      <Button
                        variant="ghost"
                        disabled={ro || form.returnScenarios.length <= 1}
                        onClick={() => {
                          const nextScenarios = form.returnScenarios.filter((_, i) => i !== idx);
                          const nextRef =
                            form.referenceScenarioIndex === idx
                              ? Math.floor(nextScenarios.length / 2)
                              : form.referenceScenarioIndex > idx
                                ? form.referenceScenarioIndex - 1
                                : form.referenceScenarioIndex;
                          patch({ returnScenarios: nextScenarios, referenceScenarioIndex: nextRef });
                        }}
                      >
                        {t("common.delete")}
                      </Button>
                    </div>
                  </div>
                );
              })}
              <div className="flex flex-wrap gap-2 pt-1">
                <Button
                  variant="secondary"
                  disabled={ro}
                  onClick={() =>
                    addScenario({
                      name: t("fire.scenario_default_n", { n: form.returnScenarios.length + 1 }),
                      meanPercent: "6.0",
                      stdDevPercent: "12.0",
                      historical: false,
                    })
                  }
                >
                  {t("fire.add_scenario")}
                </Button>
                <span title={projection?.historicalScenario ? t("fire.historical_tooltip", { years: projection.historicalScenario.yearsOfData }) : t("fire.add_historical_disabled")}>
                  <Button
                    variant="secondary"
                    disabled={ro || !projection?.historicalScenario}
                    onClick={() => {
                      const h = projection?.historicalScenario;
                      if (h) {
                        addScenario({
                          name: t("fire.historical_ref"),
                          meanPercent: h.meanPercent,
                          stdDevPercent: h.stdDevPercent,
                          historical: true,
                        });
                      }
                    }}
                  >
                    {t("fire.add_historical")}
                  </Button>
                </span>
                <span title={t("fire.suggest_asset_mix_help")}>
                  <Button
                    variant="secondary"
                    disabled={ro || !projection?.suggestedScenario}
                    onClick={() => {
                      const sug = projection?.suggestedScenario;
                      if (sug) {
                        addScenario({
                          ...sug,
                          name: sug.name || t("fire.suggest_asset_mix"),
                        });
                      }
                    }}
                  >
                    {t("fire.suggest_asset_mix")}
                  </Button>
                </span>
                <Button
                  variant="secondary"
                  disabled={ro}
                  onClick={() => setConfirmRestoreOpen(true)}
                >
                  {t("fire.scenarios_restore_defaults")}
                </Button>
              </div>
              {confirmRestoreOpen && (
                <div className="mt-2 space-y-2 rounded-md border border-amber-300 bg-amber-50/80 p-3 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-100">
                  <p className="font-medium">{t("fire.scenarios_restore_confirm_title")}</p>
                  <p>{t("fire.scenarios_restore_confirm_body")}</p>
                  <ul className="list-disc space-y-0.5 pl-4 tabular-nums">
                    {form.returnScenarios.map((sc, idx) => (
                      <li key={idx}>
                        {scenarioDisplayName(form.returnScenarios, idx, t)} ({pct(sc.meanPercent)} ±{pct(sc.stdDevPercent)})
                      </li>
                    ))}
                  </ul>
                  <div className="flex flex-wrap gap-2 pt-1">
                    <Button
                      variant="secondary"
                      disabled={ro}
                      onClick={() => {
                        patch({
                          returnScenarios: SEED_RETURN_SCENARIOS.map((seed) => ({
                            name: "",
                            meanPercent: seed.meanPercent,
                            stdDevPercent: seed.stdDevPercent,
                            historical: false,
                          })),
                          referenceScenarioIndex: 1,
                        });
                        setConfirmRestoreOpen(false);
                      }}
                    >
                      {t("fire.scenarios_restore_confirm_cta")}
                    </Button>
                    <Button variant="ghost" onClick={() => setConfirmRestoreOpen(false)}>
                      {t("common.cancel")}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </AdvancedSection>

          {/* 3. Retirement drawdown years and Fat FIRE multiplier */}
          <AdvancedSection title={t("fire.settings_drawdown_fat_title")} summary={drawdownFatSummary}>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <Label>{t("fire.drawdown_years")}</Label>
                <Input
                  type="number"
                  min="1"
                  max="80"
                  value={form.drawdownYears}
                  disabled={ro}
                  invalid={!!errors.drawdownYears}
                  onChange={(e) => patch({ drawdownYears: Number(e.target.value) }, ["drawdownYears"])}
                />
                <FieldError message={errors.drawdownYears} />
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.drawdown_years_help")}</p>
              </div>
              <div>
                <Label>{t("fire.fat_multiplier")}</Label>
                <Input
                  type="number"
                  step="0.1"
                  min="1"
                  value={form.fatMultiplier}
                  disabled={ro}
                  invalid={!!errors.fatMultiplier}
                  onChange={(e) => patch({ fatMultiplier: e.target.value }, ["fatMultiplier"])}
                />
                <FieldError message={errors.fatMultiplier} />
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{fatEffect}</p>
              </div>
            </div>
          </AdvancedSection>

          {/* 4. What counts as investment (qualifying classes) */}
          <AdvancedSection title={t("fire.settings_qualifying_plain")} summary={qualifyingSummary}>
            <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.settings_qualifying_tech")} · {t("fire.qualifying_help")}
            </p>
            <div className={`flex flex-wrap gap-2 ${errors.qualifying ? "rounded-md p-1 ring-1 ring-red-500" : ""}`}>
              {assetClasses.map((cls) => {
                const active = form.qualifyingAssetClasses.includes(cls.code);
                return (
                  <button
                    key={cls.code}
                    type="button"
                    disabled={ro}
                    onClick={() => {
                      const next = active
                        ? form.qualifyingAssetClasses.filter((x) => x !== cls.code)
                        : [...form.qualifyingAssetClasses, cls.code];
                      patch({ qualifyingAssetClasses: next }, ["qualifying"]);
                    }}
                    className={`rounded-full border px-3 py-1 text-sm disabled:cursor-not-allowed disabled:opacity-60 ${active ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border-strong bg-raised text-gray-700 dark:text-gray-200"}`}
                  >
                    {t(`asset.${cls.code}`)}
                  </button>
                );
              })}
            </div>
            <FieldError message={errors.qualifying} />
          </AdvancedSection>

          {/* 5. Capital-gains tax */}
          <AdvancedSection title={t("fire.tax_title")} summary={taxSummary}>
            <div className="space-y-3">
              <div className="max-w-xl">
                <Toggle
                  checked={form.applyCapitalGainsTax}
                  disabled={ro}
                  onChange={(v) => patch({ applyCapitalGainsTax: v })}
                  label={t("fire.tax_apply")}
                />
                <p className="text-xs text-gray-500 dark:text-gray-400">{taxEffect}</p>
              </div>

              {form.applyCapitalGainsTax && (
                <div className="space-y-3 pt-1">
                  <div className="max-w-xl">
                    <Toggle
                      checked={form.indexTaxBrackets}
                      disabled={ro}
                      onChange={(v) => patch({ indexTaxBrackets: v })}
                      label={t("fire.index_tax_brackets")}
                    />
                    <p className="text-xs text-gray-500 dark:text-gray-400">{t("fire.index_tax_brackets_help")}</p>
                  </div>
                  <div className="max-w-md">
                    <Label>{t("fire.settings_gain_share_plain")}</Label>
                    <p className="mb-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.gain_fraction")}</p>
                    <Input
                      type="number"
                      step="1"
                      min="0"
                      max="100"
                      className="max-w-xs"
                      value={form.fallbackGainFractionPct}
                      disabled={ro}
                      invalid={!!errors.gainFraction}
                      onChange={(e) => patch({ fallbackGainFractionPct: e.target.value }, ["gainFraction"])}
                    />
                    <FieldError message={errors.gainFraction} />
                    <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                      {t("fire.gain_fraction_effect")}{" "}
                      {projection && (projection.gainFraction.source === "movements"
                        ? t("fire.gain_fraction_movements", { pct: formatFirePercent(Number(projection.gainFraction.percent), locale, 2) })
                        : t("fire.gain_fraction_manual", { pct: formatFirePercent(Number(projection.gainFraction.percent), locale, 2) }))}
                    </p>
                    {projection && projection.gainFraction.source === "movements" && projection.gainFraction.uncoveredMonths > 0 && (
                      <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
                        {t("fire.gain_fraction_coverage_warning", {
                          gain: formatFirePercent(Number(projection.gainFraction.percent), locale, 2),
                          months: projection.gainFraction.uncoveredMonths,
                        })}
                      </p>
                    )}
                  </div>

                  <div>
                    <Label>{t("fire.tax_brackets")}</Label>
                    <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("fire.tax_brackets_help")}</p>
                    <div className="max-w-md space-y-2">
                      {form.taxBrackets.map((b, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <div className="flex-1">
                            {idx === 0 && <Label className="text-xs">{t("fire.tax_lower_bound")}</Label>}
                            <Input
                              type="number"
                              step="0.01"
                              min="0"
                              value={b.lowerBound}
                              disabled={ro}
                              invalid={!!errors.brackets}
                              onChange={(e) => {
                                const next = form.taxBrackets.map((x, i) => (i === idx ? { ...x, lowerBound: e.target.value } : x));
                                patch({ taxBrackets: next }, ["brackets"]);
                              }}
                            />
                          </div>
                          <div className="flex-1">
                            {idx === 0 && <Label className="text-xs">{t("fire.tax_rate")}</Label>}
                            <Input
                              type="number"
                              step="0.1"
                              min="0"
                              max="100"
                              value={b.ratePct}
                              disabled={ro}
                              invalid={!!errors.brackets}
                              onChange={(e) => {
                                const next = form.taxBrackets.map((x, i) => (i === idx ? { ...x, ratePct: e.target.value } : x));
                                patch({ taxBrackets: next }, ["brackets"]);
                              }}
                            />
                          </div>
                          <Button
                            variant="ghost"
                            className={idx === 0 ? "mt-5" : ""}
                            disabled={ro || form.taxBrackets.length <= 1}
                            onClick={() => patch({ taxBrackets: form.taxBrackets.filter((_, i) => i !== idx) }, ["brackets"])}
                          >
                            {t("common.delete")}
                          </Button>
                        </div>
                      ))}
                      <FieldError message={errors.brackets} />
                      <Button
                        variant="secondary"
                        disabled={ro}
                        onClick={() => {
                          const lastBound = Number(form.taxBrackets[form.taxBrackets.length - 1]?.lowerBound ?? 0);
                          patch({
                            taxBrackets: [...form.taxBrackets, { lowerBound: String(lastBound + 50000), ratePct: "30.0" }],
                          }, ["brackets"]);
                        }}
                      >
                        {t("fire.add_bracket")}
                      </Button>
                    </div>
                    <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("fire.tax_model_note")}</p>
                  </div>
                </div>
              )}
            </div>
          </AdvancedSection>

          {/* 6. Cash-flow events & life phases */}
          <AdvancedSection title={t("fire.cash_flow_events")} summary={eventsSummaryText}>
            <div className="space-y-2">
              <p className="text-xs text-gray-500 dark:text-gray-400">{t("fire.cash_flow_events_help")}</p>
              {form.cashFlowEvents.map((ev, idx) => (
                <div key={idx} className="grid grid-cols-1 gap-2 rounded-md border border-border p-2 sm:grid-cols-12 sm:items-end">
                  <div className="sm:col-span-2">
                    <Label className="text-xs">{t("fire.event_year")}</Label>
                    <Input
                      type="number"
                      value={ev.year}
                      disabled={ro}
                      onChange={(e) => {
                        const next = form.cashFlowEvents.map((x, i) => (i === idx ? { ...x, year: Number(e.target.value) } : x));
                        patch({ cashFlowEvents: next }, ["cashFlowEvents"]);
                      }}
                    />
                  </div>
                  <div className="sm:col-span-3">
                    <Label className="text-xs">{t("fire.event_kind")}</Label>
                    <Select
                      value={ev.kind}
                      disabled={ro}
                      onChange={(e) => {
                        const next = form.cashFlowEvents.map((x, i) =>
                          i === idx ? { ...x, kind: e.target.value as CashFlowEventKind } : x,
                        );
                        patch({ cashFlowEvents: next }, ["cashFlowEvents"]);
                      }}
                    >
                      <option value="lump_sum">{t("fire.event_lump_sum")}</option>
                      <option value="contribution_delta">{t("fire.event_contribution_delta")}</option>
                      <option value="spending_delta">{t("fire.event_spending_delta")}</option>
                    </Select>
                  </div>
                  <div className="sm:col-span-3">
                    <Label className="text-xs">{t("fire.event_amount")} ({currency})</Label>
                    <Input
                      type="number"
                      step="0.01"
                      value={ev.amount}
                      disabled={ro}
                      onChange={(e) => {
                        const next = form.cashFlowEvents.map((x, i) => (i === idx ? { ...x, amount: e.target.value } : x));
                        patch({ cashFlowEvents: next }, ["cashFlowEvents"]);
                      }}
                    />
                  </div>
                  <div className="sm:col-span-3">
                    <Label className="text-xs">{t("fire.event_label")}</Label>
                    <Input
                      type="text"
                      value={ev.label}
                      disabled={ro}
                      placeholder={t("fire.event_label_placeholder")}
                      onChange={(e) => {
                        const next = form.cashFlowEvents.map((x, i) => (i === idx ? { ...x, label: e.target.value } : x));
                        patch({ cashFlowEvents: next }, ["cashFlowEvents"]);
                      }}
                    />
                  </div>
                  <div className="sm:col-span-1">
                    <Button
                      variant="ghost"
                      disabled={ro}
                      onClick={() => patch({ cashFlowEvents: form.cashFlowEvents.filter((_, i) => i !== idx) }, ["cashFlowEvents"])}
                    >
                      {t("common.delete")}
                    </Button>
                  </div>
                  {eventProblems
                    .filter((p) => p.index === idx)
                    .map((p) => (
                      <p
                        key={p.problem}
                        className={cn(
                          "text-xs sm:col-span-12",
                          p.problem === "not_simulated" ? "text-red-600 dark:text-red-400" : "text-amber-700 dark:text-amber-300",
                        )}
                      >
                        {p.problem === "not_simulated"
                          ? t("fire.event_lump_not_simulated", { year: p.firstYear - 1, firstYear: p.firstYear })
                          : t("fire.event_lump_after_horizon", { horizon: form.targetYear })}
                      </p>
                    ))}
                </div>
              ))}
              <FieldError message={errors.cashFlowEvents} />
              <div className="flex flex-wrap gap-2">
              {projection && (
                <FireEventTemplates
                  householdId={householdId}
                  projection={projection}
                  events={form.cashFlowEvents}
                  currency={currency}
                  disabled={ro}
                  onAdd={(added) => patch({ cashFlowEvents: [...form.cashFlowEvents, ...added] }, ["cashFlowEvents"])}
                />
              )}
              <Button
                variant="secondary"
                disabled={ro}
                onClick={() =>
                  patch(
                    {
                      cashFlowEvents: [
                        ...form.cashFlowEvents,
                        { year: (projection?.startYear ?? 2026) + 5, kind: "contribution_delta", amount: "500.00", label: "" },
                      ],
                    },
                    ["cashFlowEvents"],
                  )
                }
              >
                {t("fire.add_cash_flow_event")}
              </Button>
              </div>
            </div>
          </AdvancedSection>
        </section>

        {serverError && <p className="text-sm text-red-600">{serverError}</p>}
        <div className="flex items-center gap-3">
          <Button disabled={ro || saving} onClick={onSave}>
            {saving ? t("common.saving") : t("common.save")}
          </Button>
          {saved && <span className="text-sm text-emerald-600">{t("common.saved")}</span>}
        </div>
      </CardBody>
    </Card>
  );
}
