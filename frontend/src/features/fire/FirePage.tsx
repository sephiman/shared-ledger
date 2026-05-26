import { Fragment, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useFireProjection, useFireSettings, useUpdateFireSettings, type FireSettings, type ReturnScenario } from "@/api/fire";
import { useAssetClasses } from "@/api/catalog";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";
import { Area, CartesianGrid, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatMoney } from "@/lib/money";
import { ChartTooltip } from "@/components/charts/ChartTooltip";

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444"];

function scenarioKey(s: { meanPercent: string; stdDevPercent: string }): string {
  return `${s.meanPercent}_${s.stdDevPercent}`;
}

export function FirePage() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: settings } = useFireSettings(household.householdId);
  const { data: projection } = useFireProjection(household.householdId);
  const { data: assetClasses = [] } = useAssetClasses();
  const update = useUpdateFireSettings(household.householdId);

  const [form, setForm] = useState<FireSettings | null>(null);
  const [errors, setErrors] = useState<{ targetAmount?: string; targetYear?: string; monthlyContribution?: string; qualifying?: string; scenarios?: Array<{ meanPercent?: string; stdDevPercent?: string }> }>({});
  useEffect(() => { if (settings) setForm(settings); }, [settings]);

  function clearFieldError(key: "targetAmount" | "targetYear" | "monthlyContribution" | "qualifying") {
    if (errors[key]) setErrors({ ...errors, [key]: undefined });
  }

  function clearScenarioError(idx: number, field: "meanPercent" | "stdDevPercent") {
    if (!errors.scenarios?.[idx]?.[field]) return;
    const sc = [...(errors.scenarios ?? [])];
    sc[idx] = { ...sc[idx], [field]: undefined };
    setErrors({ ...errors, scenarios: sc });
  }

  function validateAndSave() {
    if (!form) return;
    const next: typeof errors = {};
    if (!form.targetAmount.trim()) next.targetAmount = t("errors.field_required");
    else if (!Number.isFinite(Number(form.targetAmount)) || Number(form.targetAmount) <= 0) next.targetAmount = t("errors.amount_positive");
    if (!Number.isFinite(form.targetYear) || form.targetYear <= 0) next.targetYear = t("errors.number_required");
    if (!form.monthlyContribution.trim()) next.monthlyContribution = t("errors.field_required");
    else if (!Number.isFinite(Number(form.monthlyContribution))) next.monthlyContribution = t("errors.number_required");
    if (form.qualifyingAssetClasses.length === 0) next.qualifying = t("errors.select_at_least_one");
    const scenarioErrs: Array<{ meanPercent?: string; stdDevPercent?: string }> = [];
    let anyScenarioErr = false;
    form.returnScenarios.forEach((sc, idx) => {
      const row: { meanPercent?: string; stdDevPercent?: string } = {};
      if (!sc.meanPercent.trim() || !Number.isFinite(Number(sc.meanPercent))) { row.meanPercent = t("errors.number_required"); anyScenarioErr = true; }
      if (!sc.stdDevPercent.trim() || !Number.isFinite(Number(sc.stdDevPercent)) || Number(sc.stdDevPercent) < 0) { row.stdDevPercent = t("errors.number_required"); anyScenarioErr = true; }
      scenarioErrs[idx] = row;
    });
    if (anyScenarioErr) next.scenarios = scenarioErrs;
    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }
    setErrors({});
    update.mutate(form);
  }

  const chartData = useMemo(() => {
    if (!projection) return [];
    const yearSet = new Set<number>();
    for (const s of projection.scenarios) {
      for (const p of s.percentiles) yearSet.add(p.year);
    }
    return Array.from(yearSet).sort((a, b) => a - b).map((year) => {
      const row: Record<string, number | string> = { year };
      for (const s of projection.scenarios) {
        const p = s.percentiles.find((x) => x.year === year);
        if (p) {
          const k = scenarioKey(s);
          row[`${k}_p50`] = Number(p.p50);
          // For the area band we send [p10, p90] as a tuple via the dataKey accessor below.
          row[`${k}_p10`] = Number(p.p10);
          row[`${k}_p90`] = Number(p.p90);
        }
      }
      return row;
    });
  }, [projection]);

  function setScenarioField(idx: number, patch: Partial<ReturnScenario>) {
    if (!form) return;
    const next = form.returnScenarios.map((s, i) => (i === idx ? { ...s, ...patch } : s));
    setForm({ ...form, returnScenarios: next });
  }

  function addScenario() {
    if (!form) return;
    setForm({
      ...form,
      returnScenarios: [...form.returnScenarios, { meanPercent: "6.0", stdDevPercent: "12.0" }],
    });
  }

  function removeScenario(idx: number) {
    if (!form) return;
    setForm({ ...form, returnScenarios: form.returnScenarios.filter((_, i) => i !== idx) });
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">{t("fire.title")}</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("fire.description")}</p>
      </div>

      {form && (
        <Card>
          <CardHeader>
            <p className="font-medium">{t("nav.settings")}</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("fire.settings_description")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <div>
                <Label>{t("fire.target_amount")}</Label>
                <Input
                  type="number"
                  step="0.01"
                  value={form.targetAmount}
                  invalid={!!errors.targetAmount}
                  onChange={(e) => { setForm({ ...form, targetAmount: e.target.value }); clearFieldError("targetAmount"); }}
                />
                <FieldError message={errors.targetAmount} />
              </div>
              <div>
                <Label>{t("fire.target_year")}</Label>
                <Input
                  type="number"
                  value={form.targetYear}
                  invalid={!!errors.targetYear}
                  onChange={(e) => { setForm({ ...form, targetYear: Number(e.target.value) }); clearFieldError("targetYear"); }}
                />
                <FieldError message={errors.targetYear} />
              </div>
              <div>
                <Label>{t("fire.monthly_contribution")}</Label>
                <Input
                  type="number"
                  step="0.01"
                  value={form.monthlyContribution}
                  invalid={!!errors.monthlyContribution}
                  onChange={(e) => { setForm({ ...form, monthlyContribution: e.target.value }); clearFieldError("monthlyContribution"); }}
                />
                <FieldError message={errors.monthlyContribution} />
              </div>
            </div>

            <div>
              <Label>{t("fire.scenarios")}</Label>
              <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("fire.scenarios_help")}</p>
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
                          invalid={!!meanErr}
                          onChange={(e) => { setScenarioField(idx, { meanPercent: e.target.value }); clearScenarioError(idx, "meanPercent"); }}
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
                          invalid={!!stdErr}
                          onChange={(e) => { setScenarioField(idx, { stdDevPercent: e.target.value }); clearScenarioError(idx, "stdDevPercent"); }}
                        />
                        <FieldError message={stdErr} />
                      </div>
                      <Button className="mt-5" variant="ghost" onClick={() => removeScenario(idx)} disabled={form.returnScenarios.length <= 1}>
                        {t("common.delete")}
                      </Button>
                    </div>
                  );
                })}
                <Button variant="secondary" onClick={addScenario}>{t("fire.add_scenario")}</Button>
              </div>
            </div>

            <div>
              <Label>{t("fire.qualifying")}</Label>
              <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("fire.qualifying_help")}</p>
              <div className={`flex flex-wrap gap-2 ${errors.qualifying ? "rounded-md p-1 ring-1 ring-red-500" : ""}`}>
                {assetClasses.map((cls) => {
                  const active = form.qualifyingAssetClasses.includes(cls.code);
                  return (
                    <button
                      key={cls.code}
                      type="button"
                      onClick={() => {
                        const next = active
                          ? form.qualifyingAssetClasses.filter((x) => x !== cls.code)
                          : [...form.qualifyingAssetClasses, cls.code];
                        setForm({ ...form, qualifyingAssetClasses: next });
                        clearFieldError("qualifying");
                      }}
                      className={`rounded-full border px-3 py-1 text-sm ${active ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-white text-gray-700 dark:bg-gray-700 dark:text-gray-200 dark:border-gray-600"}`}
                    >
                      {t(`asset.${cls.code}`)}
                    </button>
                  );
                })}
              </div>
              <FieldError message={errors.qualifying} />
            </div>
            <div className="flex justify-end">
              <Button onClick={validateAndSave}>{t("common.save")}</Button>
            </div>
          </CardBody>
        </Card>
      )}

      {projection && (
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <p className="font-medium">{t("fire.projection")} ({projection.monteCarloTrials.toLocaleString(i18n.language)} {t("fire.trials")})</p>
              <p className="text-sm text-gray-600 dark:text-gray-300">
                {t("fire.actual_return")}: {projection.actualAnnualizedReturnPercent ? `${projection.actualAnnualizedReturnPercent}%` : t("fire.needs_snapshots")}
              </p>
            </div>
          </CardHeader>
          <CardBody className="h-96">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="year" />
                <YAxis />
                <Tooltip
                  content={(props) => (
                    <ChartTooltip
                      {...props}
                      formatValue={(v) => {
                        if (Array.isArray(v)) {
                          const lo = formatMoney(String(v[0]), household.currency, i18n.language);
                          const hi = formatMoney(String(v[1]), household.currency, i18n.language);
                          return `${lo} – ${hi}`;
                        }
                        return formatMoney(String(v ?? "0"), household.currency, i18n.language);
                      }}
                    />
                  )}
                />
                <Legend />
                {projection.scenarios.map((s, idx) => {
                  const k = scenarioKey(s);
                  const color = PALETTE[idx % PALETTE.length];
                  const label = `${s.meanPercent}% ±${s.stdDevPercent}%`;
                  return (
                    <Fragment key={k}>
                      <Area
                        type="monotone"
                        dataKey={(row: Record<string, number>) => [row[`${k}_p10`], row[`${k}_p90`]]}
                        stroke="none"
                        fill={color}
                        fillOpacity={0.12}
                        legendType="none"
                        activeDot={false}
                        name={`${label} band`}
                      />
                      <Line
                        type="monotone"
                        dataKey={`${k}_p50`}
                        name={label}
                        stroke={color}
                        strokeWidth={2}
                        dot={false}
                      />
                    </Fragment>
                  );
                })}
                <ReferenceLine y={Number(projection.settings.targetAmount)} stroke="#111827" strokeDasharray="6 3" label={t("fire.target_amount")} />
              </ComposedChart>
            </ResponsiveContainer>
          </CardBody>
        </Card>
      )}

      {projection && (
        <Card>
          <CardHeader>
            <p className="font-medium">{t("fire.summary")}</p>
          </CardHeader>
          <CardBody>
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500 dark:text-gray-400">
                <tr>
                  <th className="py-2">{t("fire.scenario")}</th>
                  <th>{t("fire.target_hit_deterministic")}</th>
                  <th>{t("fire.probability")}</th>
                  <th>{t("fire.median_hit")}</th>
                </tr>
              </thead>
              <tbody>
                {projection.scenarios.map((s, idx) => (
                  <tr key={`${s.meanPercent}_${s.stdDevPercent}_${idx}`} className="border-t border-border">
                    <td className="py-2">{s.meanPercent}% ±{s.stdDevPercent}%</td>
                    <td>{s.targetHitYear ?? "—"}</td>
                    <td>{(s.probabilityOfReachingTarget * 100).toFixed(0)}%</td>
                    <td>{s.medianYearReachingTarget ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.target_amount")}: {formatMoney(projection.settings.targetAmount, household.currency, i18n.language)}
            </p>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
