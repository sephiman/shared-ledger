import { useTranslation } from "react-i18next";
import type { FireProjection, FireSettings, FireTierKey, ReturnScenario } from "@/api/fire";
import { Badge, Button, Card, CardBody, CardHeader, FieldError, Input, Label, Toggle } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { TIER_ORDER } from "./fireLabels";

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
  scenarios?: Array<{ meanPercent?: string; stdDevPercent?: string }>;
}

interface Props {
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

/** Every FIRE parameter lives here (not general Settings) and persists per household. Each setting carries
 *  a terse repercussion line instantiated with the household's own numbers when the projection has them. */
export function FireSettingsCard({
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
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);
  const ro = !isOwner;

  const saved_ = projection?.settings;
  const fireTier = projection?.tiers.find((x) => x.key === "fire" && x.targetToday !== null);
  const fatTier = projection?.tiers.find((x) => x.key === "fat" && x.targetToday !== null);

  const swrEffect = (() => {
    if (!fireTier || !saved_) return t("fire.swr_effect_generic");
    const swr = Number(saved_.safeWithdrawalRatePct);
    const alt = swr > 1 ? swr - 0.5 : swr + 0.5;
    const altTarget = (Number(fireTier.targetToday) * swr) / alt;
    return t("fire.swr_effect", {
      swr: saved_.safeWithdrawalRatePct,
      target: money(fireTier.targetToday),
      alt: alt.toFixed(1),
      altTarget: money(altTarget),
    });
  })();

  const inflationEffect = (() => {
    if (!fireTier || !saved_ || !projection) return t("fire.inflation_effect_generic");
    // Compound today's FIRE target over whole years from the latest-snapshot year, so the
    // figure is hand-reproducible: targetToday × (1 + inflation)^N. (The chart's dashed curve
    // additionally re-grosses-up the tax per year; this headline "grows at inflation" line
    // deliberately does not, keeping it verifiable from the two on-screen numbers.)
    const n = Math.max(0, Number(saved_.targetYear) - projection.startYear);
    if (n <= 0) return t("fire.inflation_effect_generic");
    const projected = Number(fireTier.targetToday) * (1 + Number(saved_.expectedInflationPct) / 100) ** n;
    return t("fire.inflation_effect", { target: money(projected), year: saved_.targetYear });
  })();

  const fatEffect = fatTier && saved_
    ? t("fire.fat_multiplier_effect", { multiplier: saved_.fatMultiplier, target: money(fatTier.targetToday) })
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

  function setScenarioField(idx: number, field: "meanPercent" | "stdDevPercent", value: string) {
    const next = form.returnScenarios.map((s, i) => (i === idx ? { ...s, [field]: value } : s));
    patch({ returnScenarios: next });
    onScenarioErrorClear(idx, field);
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

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("nav.settings")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("fire.settings_description")}</p>
        {ro && <p className="mt-1 text-sm text-amber-700 dark:text-amber-300">{t("fire.owners_only")}</p>}
      </CardHeader>
      <CardBody className="space-y-6">
        {/* Framework: horizon, SWR, inflation, indexation, Fat multiplier */}
        <section>
          <p className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-200">{t("fire.framework")}</p>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
            <div>
              <Label>{t("fire.target_year")}</Label>
              <Input
                type="number"
                value={form.targetYear}
                disabled={ro}
                invalid={!!errors.targetYear}
                onChange={(e) => patch({ targetYear: Number(e.target.value) }, ["targetYear"])}
              />
              <FieldError message={errors.targetYear} />
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.target_year_effect")}</p>
            </div>
            <div>
              <Label>{t("fire.swr")}</Label>
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
              <Label>{t("fire.inflation")}</Label>
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
          <div className="mt-2 max-w-md">
            <Toggle
              checked={form.indexContribution}
              disabled={ro}
              onChange={(v) => patch({ indexContribution: v })}
              label={t("fire.index_contribution")}
            />
            <p className="text-xs text-gray-500 dark:text-gray-400">
              {form.indexContribution
                ? t("fire.index_contribution_effect_on", { inflation: form.expectedInflationPct })
                : t("fire.index_contribution_effect_off")}
            </p>
          </div>
        </section>

        {/* Tiers shown + custom target */}
        <section>
          <p className="mb-2 text-sm font-semibold text-gray-700 dark:text-gray-200">{t("fire.tier_toggles")}</p>
          <div className="flex flex-wrap gap-4">
            {TIER_ORDER.map((key) => (
              <label key={key} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                  checked={Boolean(form[tierFlag[key]])}
                  disabled={ro}
                  onChange={(e) => patch({ [tierFlag[key]]: e.target.checked } as Partial<FireSettings>)}
                />
                {t(`fire.tier_${key}`)}
              </label>
            ))}
          </div>
          {form.tierCustomEnabled && (
            <div className="mt-2 max-w-xs">
              <Label>{t("fire.target_amount")}</Label>
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
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("fire.custom_target_help")}</p>
            </div>
          )}
        </section>

        {/* Spending bases: derived from data by default, manually overridable, both always visible */}
        <section>
          <p className="mb-1 text-sm font-semibold text-gray-700 dark:text-gray-200">{t("fire.spending_bases")}</p>
          <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("fire.spending_bases_help")}</p>
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
              <div key={base} className="mb-3">
                <Label>{t(`fire.spending_${base}`)}</Label>
                <div className="space-y-2">
                  <div className="rounded-md border border-border p-2">
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="radio"
                        name={`fire-spending-${base}`}
                        className="h-4 w-4 border-border text-primary focus:ring-primary"
                        checked={mode === "derived"}
                        disabled={ro}
                        onChange={() => patch({ [modeField]: "derived" } as Partial<FireSettings>)}
                      />
                      <span className="flex-1">{t("fire.spending_derived")}</span>
                      <span className="font-medium">
                        {hasDerivedData ? money(derived) : t("fire.contribution_no_data")}
                      </span>
                    </label>
                    <p className="ml-6 mt-1 text-xs text-gray-500 dark:text-gray-400">
                      {t(`fire.spending_${base}_derived_explain`)}
                    </p>
                  </div>
                  <div className="rounded-md border border-border p-2">
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="radio"
                        name={`fire-spending-${base}`}
                        className="h-4 w-4 border-border text-primary focus:ring-primary"
                        checked={mode === "manual"}
                        disabled={ro}
                        onChange={() => patch({ [modeField]: "manual" } as Partial<FireSettings>)}
                      />
                      <span className="flex-1">{t("fire.spending_manual")}</span>
                      <Input
                        type="number"
                        step="0.01"
                        min="0"
                        className="w-36"
                        value={manualValue}
                        disabled={ro}
                        invalid={!!error}
                        onChange={(e) => patch({ [valueField]: e.target.value } as Partial<FireSettings>, [errKey])}
                      />
                    </label>
                    <FieldError message={error} />
                  </div>
                </div>
                {mode === "manual" && hasDerivedData && (
                  <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
                    {t(`fire.spending_${base}_gap`, { derived: money(derived), manual: money(manualValue) })}
                  </p>
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
              <p className="mt-1 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
                {t("fire.spending_coherence_warning")}
              </p>
            ) : null;
          })()}
        </section>

        {/* Contribution: three sources, all visible */}
        <section>
          <p className="mb-1 text-sm font-semibold text-gray-700 dark:text-gray-200">{t("fire.contribution_mode")}</p>
          <div className="space-y-2">
            {(["manual", "savings", "movements"] as const).map((mode) => {
              const derived = projection?.contributions;
              const value =
                mode === "manual" ? form.monthlyContribution
                : mode === "savings" ? derived?.savingsMonthly ?? null
                : derived?.movementsMonthly ?? null;
              return (
                <div key={mode} className="rounded-md border border-border p-2">
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="radio"
                      name="fire-contribution-mode"
                      className="h-4 w-4 border-border text-primary focus:ring-primary"
                      checked={form.contributionMode === mode}
                      disabled={ro}
                      onChange={() => patch({ contributionMode: mode })}
                    />
                    <span className="flex-1">{t(`fire.contribution_${mode}`)}</span>
                    {mode === "manual" ? (
                      <Input
                        type="number"
                        step="0.01"
                        className="w-36"
                        value={form.monthlyContribution}
                        disabled={ro}
                        invalid={!!errors.monthlyContribution}
                        onChange={(e) => patch({ monthlyContribution: e.target.value }, ["monthlyContribution"])}
                      />
                    ) : (
                      <span className="font-medium">
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
          {projection?.contributions.savingsMonthly != null && projection.contributions.movementsMonthly != null &&
            form.contributionMode !== "manual" && (
            <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">
              {t("fire.contribution_gap", {
                savings: money(projection.contributions.savingsMonthly),
                movements: money(projection.contributions.movementsMonthly),
              })}
            </p>
          )}
        </section>

        {/* Return scenarios */}
        <section>
          <Label>{t("fire.scenarios")}</Label>
          <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">
            {t("fire.scenarios_help")} {t("fire.scenarios_nominal_note")}
          </p>
          <div className="space-y-2">
            {form.returnScenarios.map((sc, idx) => {
              const meanErr = errors.scenarios?.[idx]?.meanPercent;
              const stdErr = errors.scenarios?.[idx]?.stdDevPercent;
              return (
                <div key={idx} className="flex items-start gap-2">
                  <div className="flex-1">
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
                  <div className="flex-1">
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
                  <div className="mt-6 flex items-center gap-2">
                    {sc.historical && (
                      <Badge tone="sky" className="whitespace-nowrap" >
                        <span title={t("fire.historical_tooltip", { years: projection?.historicalScenario?.yearsOfData ?? 0 })}>
                          {t("fire.historical_ref")}
                        </span>
                      </Badge>
                    )}
                    <Button
                      variant="ghost"
                      disabled={ro || form.returnScenarios.length <= 1}
                      onClick={() => patch({ returnScenarios: form.returnScenarios.filter((_, i) => i !== idx) })}
                    >
                      {t("common.delete")}
                    </Button>
                  </div>
                </div>
              );
            })}
            <div className="flex flex-wrap gap-2">
              <Button
                variant="secondary"
                disabled={ro}
                onClick={() => addScenario({ meanPercent: "6.0", stdDevPercent: "12.0", historical: false })}
              >
                {t("fire.add_scenario")}
              </Button>
              <span title={projection?.historicalScenario ? t("fire.historical_tooltip", { years: projection.historicalScenario.yearsOfData }) : t("fire.add_historical_disabled")}>
                <Button
                  variant="secondary"
                  disabled={ro || !projection?.historicalScenario}
                  onClick={() => {
                    const h = projection?.historicalScenario;
                    if (h) addScenario({ meanPercent: h.meanPercent, stdDevPercent: h.stdDevPercent, historical: true });
                  }}
                >
                  {t("fire.add_historical")}
                </Button>
              </span>
            </div>
          </div>
        </section>

        {/* Qualifying asset classes */}
        <section>
          <Label>{t("fire.qualifying")}</Label>
          <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("fire.qualifying_help")}</p>
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
        </section>

        {/* Capital-gains tax */}
        <section>
          <p className="mb-1 text-sm font-semibold text-gray-700 dark:text-gray-200">{t("fire.tax_title")}</p>
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
            <div className="mt-3 space-y-3">
              <div className="max-w-xs">
                <Label>{t("fire.gain_fraction")}</Label>
                <Input
                  type="number"
                  step="1"
                  min="0"
                  max="100"
                  value={form.fallbackGainFractionPct}
                  disabled={ro}
                  invalid={!!errors.gainFraction}
                  onChange={(e) => patch({ fallbackGainFractionPct: e.target.value }, ["gainFraction"])}
                />
                <FieldError message={errors.gainFraction} />
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                  {t("fire.gain_fraction_effect")}{" "}
                  {projection && (projection.gainFraction.source === "movements"
                    ? t("fire.gain_fraction_movements", { pct: formatNumber(Number(projection.gainFraction.percent), locale, 2) })
                    : t("fire.gain_fraction_manual", { pct: formatNumber(Number(projection.gainFraction.percent), locale, 2) }))}
                </p>
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
        </section>

        <div className="flex items-center justify-end gap-3">
          {serverError && <p className="text-sm text-red-600">{serverError}</p>}
          {saved && !serverError && <p className="text-sm text-emerald-600">{t("common.saved")}</p>}
          {isOwner && (
            <Button onClick={onSave} disabled={saving}>
              {t("common.save")}
            </Button>
          )}
        </div>
      </CardBody>
    </Card>
  );
}
