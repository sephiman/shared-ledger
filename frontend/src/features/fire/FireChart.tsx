import { Fragment, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Area, CartesianGrid, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { FireProjection, FireTierKey } from "@/api/fire";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { formatCompactMoney, formatMoney, formatPercent } from "@/lib/money";
import { Explain } from "./Explain";
import { niceMoneyTicks } from "./fireTicks";
import {
  TIER_COLORS,
  resolveReferenceScenarioIndex,
  resolveReferenceTierKey,
  scenarioDisplayName,
} from "./fireLabels";

const SCENARIO_PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444"];

function scenarioKey(s: { meanPercent: string; stdDevPercent: string }): string {
  return `${s.meanPercent}_${s.stdDevPercent}`;
}

export function FireChart({
  projection,
  baselineProjection,
  currency,
}: {
  projection: FireProjection;
  baselineProjection?: FireProjection;
  currency: string;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;

  const [realMode, setRealMode] = useState(false);
  const [chartView, setChartView] = useState<"accumulation" | "drawdown">("accumulation");

  const refScenarioIdx = resolveReferenceScenarioIndex(projection);
  const refTierKey = resolveReferenceTierKey(projection);

  // Toggle groups (all off by default except the reference scenario, reference target, and historical wealth).
  const [enabledOtherScenarios, setEnabledOtherScenarios] = useState<Set<number>>(new Set());
  const [enabledOtherTiers, setEnabledOtherTiers] = useState<Set<FireTierKey>>(new Set());
  const [showContributions, setShowContributions] = useState(false);

  const drawdownTiers = useMemo(() => {
    const focused = projection.scenarios[refScenarioIdx] ?? projection.scenarios[0];
    return focused?.tierStats.filter((st) => st.drawdownPercentiles.length > 0).map((st) => st.tier) ?? [];
  }, [projection.scenarios, refScenarioIdx]);
  const [drawdownTier, setDrawdownTier] = useState<FireTierKey>(refTierKey);
  const activeDrawdownTier = drawdownTiers.includes(drawdownTier)
    ? drawdownTier
    : drawdownTiers.includes(refTierKey)
      ? refTierKey
      : drawdownTiers[0] ?? "fire";

  function toggleOtherScenario(idx: number) {
    setEnabledOtherScenarios((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  }

  function toggleOtherTier(tierKey: FireTierKey) {
    setEnabledOtherTiers((prev) => {
      const next = new Set(prev);
      if (next.has(tierKey)) next.delete(tierKey);
      else next.add(tierKey);
      return next;
    });
  }

  const activeTiers = projection.tiers.filter((tier) => tier.enabled && tier.targetToday !== null);
  const visibleTiers = activeTiers.filter(
    (tier) => tier.key === refTierKey || enabledOtherTiers.has(tier.key),
  );
  const visibleScenarios = projection.scenarios
    .map((sc, idx) => ({ sc, idx }))
    .filter(({ idx }) => idx === refScenarioIdx || enabledOtherScenarios.has(idx));
  const hasHistoricalWealth = projection.historicalWealth.length > 0;
  const hasCumulativeContribs = projection.cumulativeContributions.length > 0;

  const { chartData, yTicks, minYear, maxYear } = useMemo(() => {
    const inflation = Number(projection.settings.expectedInflationPct) / 100;
    if (chartView === "drawdown") {
      const sc = projection.scenarios[refScenarioIdx] ?? projection.scenarios[0];
      const stat = sc?.tierStats.find((st) => st.tier === activeDrawdownTier);
      const pts = stat?.drawdownPercentiles ?? [];
      let maxVal = 0;
      const rows = pts.map((p) => {
        const defl = realMode && p.year > 0 ? (1 + inflation) ** p.year : 1;
        const p10 = Number(p.p10) / defl;
        const p50 = Number(p.p50) / defl;
        const p90 = Number(p.p90) / defl;
        maxVal = Math.max(maxVal, p90);
        return { year: p.year, dd_p10: p10, dd_p50: p50, dd_p90: p90 };
      });
      return {
        chartData: rows,
        yTicks: niceMoneyTicks(maxVal),
        minYear: 0,
        maxYear: pts.length > 0 ? pts[pts.length - 1].year : projection.settings.drawdownYears,
      };
    }

    const yearSet = new Set<number>();
    for (const h of projection.historicalWealth) yearSet.add(h.year);
    if (showContributions) {
      for (const c of projection.cumulativeContributions) yearSet.add(c.year);
    }
    for (const { sc } of visibleScenarios) for (const p of sc.percentiles) yearSet.add(p.year);
    for (const tier of visibleTiers) for (const point of tier.targetCurve) yearSet.add(point.year);

    const deflatorFor = (year: number) =>
      realMode && year > projection.startYear ? (1 + inflation) ** (year - projection.startYear) : 1;

    const baseScenario = baselineProjection?.scenarios[refScenarioIdx] ?? baselineProjection?.scenarios[0];

    let maxValue = 0;
    const sortedYears = Array.from(yearSet).sort((a, b) => a - b);
    const rows = sortedYears.map((year) => {
      const row: Record<string, number | string> = { year };
      const deflator = deflatorFor(year);
      const hist = projection.historicalWealth.find((x) => x.year === year);
      if (hist) {
        row.historical_wealth = Number(hist.value);
        maxValue = Math.max(maxValue, row.historical_wealth);
      }
      if (showContributions) {
        const contrib = projection.cumulativeContributions.find((x) => x.year === year);
        if (contrib) {
          row.cumulative_contributions = Number(contrib.cumulative);
          maxValue = Math.max(maxValue, row.cumulative_contributions);
        }
      }
      for (const { sc } of visibleScenarios) {
        const p = sc.percentiles.find((x) => x.year === year);
        if (p) {
          const k = scenarioKey(sc);
          row[`${k}_p50`] = Number(p.p50) / deflator;
          row[`${k}_p10`] = Number(p.p10) / deflator;
          row[`${k}_p90`] = Number(p.p90) / deflator;
          maxValue = Math.max(maxValue, row[`${k}_p90`] as number);
        }
      }
      if (baseScenario) {
        const bp = baseScenario.percentiles.find((x) => x.year === year);
        if (bp) {
          row.baseline_p50 = Number(bp.p50) / deflator;
          maxValue = Math.max(maxValue, row.baseline_p50);
        }
      }
      for (const tier of visibleTiers) {
        const point = tier.targetCurve.find((x) => x.year === year);
        if (point) {
          row[`target_${tier.key}`] = Number(point.value) / deflator;
          maxValue = Math.max(maxValue, row[`target_${tier.key}`] as number);
        }
      }
      return row;
    });
    return {
      chartData: rows,
      yTicks: niceMoneyTicks(maxValue),
      minYear: sortedYears[0] ?? projection.startYear,
      maxYear: sortedYears[sortedYears.length - 1] ?? projection.settings.targetYear,
    };
  }, [
    projection,
    baselineProjection,
    visibleScenarios,
    visibleTiers,
    showContributions,
    realMode,
    chartView,
    refScenarioIdx,
    activeDrawdownTier,
  ]);

  const s = projection.settings;
  const contributionMissing = projection.contributions.activeMonthly === null;
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);
  const otherScenarios = projection.scenarios
    .map((sc, idx) => ({ sc, idx }))
    .filter(({ idx }) => idx !== refScenarioIdx);
  const otherTiers = activeTiers.filter((tier) => tier.key !== refTierKey);

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <p className="font-medium">
              {t("fire.projection")} ({projection.monteCarloTrials.toLocaleString(locale)} {t("fire.trials")})
            </p>
            <div className="inline-flex rounded-md border border-border bg-raised p-0.5 text-xs">
              <button
                type="button"
                onClick={() => setRealMode(false)}
                className={`rounded px-2 py-1 font-medium ${!realMode ? "bg-surface text-gray-900 shadow-sm dark:text-gray-100" : "text-gray-500 hover:text-gray-700 dark:text-gray-400"}`}
              >
                {t("fire.mode_nominal")}
              </button>
              <button
                type="button"
                onClick={() => setRealMode(true)}
                className={`rounded px-2 py-1 font-medium ${realMode ? "bg-surface text-gray-900 shadow-sm dark:text-gray-100" : "text-gray-500 hover:text-gray-700 dark:text-gray-400"}`}
              >
                {t("fire.mode_real")}
              </button>
            </div>
            {drawdownTiers.length > 0 && (
              <div className="inline-flex rounded-md border border-border bg-raised p-0.5 text-xs">
                <button
                  type="button"
                  onClick={() => setChartView("accumulation")}
                  className={`rounded px-2 py-1 font-medium ${chartView === "accumulation" ? "bg-surface text-gray-900 shadow-sm dark:text-gray-100" : "text-gray-500 hover:text-gray-700 dark:text-gray-400"}`}
                >
                  {t("fire.view_accumulation")}
                </button>
                <button
                  type="button"
                  onClick={() => setChartView("drawdown")}
                  className={`rounded px-2 py-1 font-medium ${chartView === "drawdown" ? "bg-surface text-gray-900 shadow-sm dark:text-gray-100" : "text-gray-500 hover:text-gray-700 dark:text-gray-400"}`}
                >
                  {t("fire.view_drawdown", { years: s.drawdownYears })}
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Toggle groups */}
        {chartView === "accumulation" && (otherScenarios.length > 0 || otherTiers.length > 0 || hasCumulativeContribs) && (
          <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs">
            {otherScenarios.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-gray-500 dark:text-gray-400">{t("fire.chart_other_scenarios")}:</span>
                {otherScenarios.map(({ sc, idx }) => {
                  const active = enabledOtherScenarios.has(idx);
                  return (
                    <button
                      key={`${scenarioKey(sc)}_${idx}`}
                      type="button"
                      onClick={() => toggleOtherScenario(idx)}
                      className={`rounded-full border px-2.5 py-0.5 font-medium ${active ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-600 hover:text-gray-800 dark:text-gray-300"}`}
                    >
                      {scenarioDisplayName(sc, idx, t)}
                    </button>
                  );
                })}
              </div>
            )}
            {otherTiers.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-gray-500 dark:text-gray-400">{t("fire.chart_other_targets")}:</span>
                {otherTiers.map((tier) => {
                  const active = enabledOtherTiers.has(tier.key);
                  return (
                    <button
                      key={tier.key}
                      type="button"
                      onClick={() => toggleOtherTier(tier.key)}
                      className={`rounded-full border px-2.5 py-0.5 font-medium ${active ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-600 hover:text-gray-800 dark:text-gray-300"}`}
                    >
                      {tier.key === "fat"
                        ? t("fire.tier_plain_fat", { multiplier: s.fatMultiplier })
                        : t(`fire.tier_plain_${tier.key}`)}
                    </button>
                  );
                })}
              </div>
            )}
            {hasCumulativeContribs && (
              <button
                type="button"
                onClick={() => setShowContributions((v) => !v)}
                className={`rounded-full border px-2.5 py-0.5 font-medium ${showContributions ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-600 hover:text-gray-800 dark:text-gray-300"}`}
              >
                {t("fire.chart_cumulative_contributions")}
              </button>
            )}
          </div>
        )}

        {chartView === "drawdown" && drawdownTiers.length > 0 && (
          <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
            <span className="text-gray-500 dark:text-gray-400">{t("fire.tier")}:</span>
            {drawdownTiers.map((tk) => (
              <button
                key={tk}
                type="button"
                onClick={() => setDrawdownTier(tk)}
                className={`rounded-full border px-2.5 py-0.5 font-medium ${activeDrawdownTier === tk ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-600 hover:text-gray-800 dark:text-gray-300"}`}
              >
                {t(`fire.tier_${tk}`)}
              </button>
            ))}
          </div>
        )}
      </CardHeader>
      <CardBody>
        {contributionMissing && (
          <p className="mb-3 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/40 dark:text-amber-200">
            {t("fire.contribution_missing_warning")}
          </p>
        )}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <div className="lg:col-span-2">
            <div className="h-96">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="year" />
                  <YAxis
                    width={64}
                    ticks={yTicks}
                    domain={[0, yTicks[yTicks.length - 1]]}
                    tick={{ fontSize: 12 }}
                    tickFormatter={(v: number) => formatCompactMoney(v, currency, locale)}
                  />
                  <Tooltip
                    content={(props) => (
                      <ChartTooltip
                        {...props}
                        formatValue={(v) => {
                          if (Array.isArray(v)) {
                            if (v[0] == null || v[1] == null) return "—";
                            const lo = formatMoney(String(v[0]), currency, locale);
                            const hi = formatMoney(String(v[1]), currency, locale);
                            return `${lo} – ${hi}`;
                          }
                          if (v == null) return "—";
                          return formatMoney(String(v), currency, locale);
                        }}
                      />
                    )}
                  />
                  <Legend />
                  {chartView === "drawdown" ? (
                    <>
                      <Area
                        type="monotone"
                        dataKey={(row: Record<string, number>) =>
                          row.dd_p10 != null && row.dd_p90 != null ? [row.dd_p10, row.dd_p90] : undefined
                        }
                        stroke="none"
                        fill="#0ea5e9"
                        fillOpacity={0.15}
                        legendType="none"
                        activeDot={false}
                        name={t("fire.band_unlucky_to_strong")}
                      />
                      <Line
                        type="monotone"
                        dataKey="dd_p50"
                        name={t("fire.typical_market_tier", { tier: t(`fire.tier_${activeDrawdownTier}`) })}
                        stroke="#0ea5e9"
                        strokeWidth={2}
                        dot={false}
                      />
                    </>
                  ) : (
                    <>
                      {hasHistoricalWealth && (
                        <Line
                          type="monotone"
                          dataKey="historical_wealth"
                          name={t("fire.historical_wealth")}
                          stroke="#64748b"
                          strokeWidth={2}
                          dot={{ r: 2.5 }}
                          connectNulls={false}
                        />
                      )}
                      {showContributions && hasCumulativeContribs && (
                        <Line
                          type="monotone"
                          dataKey="cumulative_contributions"
                          name={t("fire.cumulative_contributions")}
                          stroke="#14b8a6"
                          strokeWidth={1.5}
                          strokeDasharray="4 4"
                          dot={{ r: 2 }}
                          connectNulls={false}
                        />
                      )}
                      {baselineProjection && (
                        <Line
                          type="monotone"
                          dataKey="baseline_p50"
                          name={t("fire.baseline_comparison")}
                          stroke="#94a3b8"
                          strokeWidth={1.5}
                          strokeDasharray="3 3"
                          dot={false}
                        />
                      )}
                      {projection.settings.cashFlowEvents
                        .filter((ev) => ev.year >= minYear && ev.year <= maxYear)
                        .map((ev, idx) => (
                          <ReferenceLine
                            key={`ev_${ev.year}_${idx}`}
                            x={ev.year}
                            stroke="#64748b"
                            strokeDasharray="3 3"
                            label={{ value: ev.label || t(`fire.event_${ev.kind}`), position: "insideTopRight", fontSize: 10, fill: "#64748b" }}
                          />
                        ))}
                      {visibleScenarios.map(({ sc, idx }) => {
                        const k = scenarioKey(sc);
                        const color = SCENARIO_PALETTE[idx % SCENARIO_PALETTE.length];
                        const label = scenarioDisplayName(sc, idx, t);
                        return (
                          <Fragment key={`${k}_${idx}`}>
                            <Area
                              type="monotone"
                              dataKey={(row: Record<string, number>) =>
                                row[`${k}_p10`] != null && row[`${k}_p90`] != null
                                  ? [row[`${k}_p10`], row[`${k}_p90`]]
                                  : undefined
                              }
                              stroke="none"
                              fill={color}
                              fillOpacity={0.12}
                              legendType="none"
                              activeDot={false}
                              name={t("fire.band_scenario_range", { label })}
                            />
                            <Line type="monotone" dataKey={`${k}_p50`} name={label} stroke={color} strokeWidth={2} dot={false} />
                          </Fragment>
                        );
                      })}
                      {visibleTiers.map((tier) => (
                        <Line
                          key={tier.key}
                          type="monotone"
                          dataKey={`target_${tier.key}`}
                          name={tier.key === "custom" ? t("fire.tier_custom") : t("fire.tier_target_name", { tier: t(`fire.tier_${tier.key}`) })}
                          stroke={TIER_COLORS[tier.key]}
                          strokeWidth={1.5}
                          strokeDasharray="6 3"
                          dot={false}
                        />
                      ))}
                    </>
                  )}
                </ComposedChart>
              </ResponsiveContainer>
            </div>

            {/* Real annualized return + warning tucked inside "How is this calculated?" */}
            <Explain className="mt-2">
              <p className="font-medium text-gray-700 dark:text-gray-200">{t("fire.chart_explain_question")}</p>
              {projection.actualReturn ? (
                <>
                  <p>
                    {t("fire.chart_explain_return", {
                      pct: projection.actualReturn.annualizedPercent,
                      from: projection.actualReturn.fromDate,
                      to: projection.actualReturn.toDate,
                      movements: projection.actualReturn.movementCount,
                    })}
                  </p>
                  <p>
                    {t("fire.actual_return_explain", {
                      from: projection.actualReturn.fromDate,
                      to: projection.actualReturn.toDate,
                      movements: projection.actualReturn.movementCount,
                    })}
                  </p>
                  {projection.actualReturn.uncoveredMonths > 0 && (
                    <p className="text-amber-700 dark:text-amber-300">
                      {t("fire.actual_return_coverage_warning", { months: projection.actualReturn.uncoveredMonths })}{" "}
                      {t("fire.actual_return_gap_explain", {
                        first: projection.actualReturn.firstMovementDate,
                        months: projection.actualReturn.uncoveredMonths,
                      })}
                    </p>
                  )}
                </>
              ) : (
                <p>{t(`fire.no_return_${projection.actualReturnUnavailableReason ?? "not_computable"}`)}</p>
              )}
            </Explain>
          </div>

          {/* Assumptions block: structured key-value summary with plain labels per §8 */}
          <aside className="rounded-md border border-border p-3 text-xs">
            <p className="mb-2.5 text-sm font-medium">{t("fire.assumptions")}</p>
            <dl className="divide-y divide-border/60">
              <div className="flex items-baseline justify-between gap-2 py-1.5">
                <dt className="text-gray-500 dark:text-gray-400">{t("fire.assumptions_wealth_label")}</dt>
                <dd className="text-right font-medium tabular-nums">
                  {money(projection.startingValue)} <span className="font-normal text-gray-500">({projection.snapshotDate ?? "—"})</span>
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2 py-1.5">
                <dt className="text-gray-500 dark:text-gray-400">{t("fire.photo_contrib_main")}</dt>
                <dd className="text-right font-medium tabular-nums">
                  {money(projection.contributions.activeMonthly ?? "0")}
                  <span className="ml-1 font-normal text-gray-500">
                    ({t(`fire.photo_contrib_source_${projection.contributions.mode}`)}
                    {s.indexContribution ? ` · ${t("fire.indexed_short")}` : ""})
                  </span>
                </dd>
              </div>
              {projection.contributions.savingsRatePct !== null && (
                <div className="flex items-baseline justify-between gap-2 py-1.5">
                  <dt className="text-gray-500 dark:text-gray-400">{t("fire.photo_savings_rate_tech")}</dt>
                  <dd className="font-medium tabular-nums">
                    {formatPercent(projection.contributions.savingsRatePct, locale, 1)}
                  </dd>
                </div>
              )}
              <div className="flex items-baseline justify-between gap-2 py-1.5">
                <dt className="text-gray-500 dark:text-gray-400">{t("fire.assumptions_spending_label")}</dt>
                <dd className="text-right font-medium tabular-nums">
                  {money(projection.spending.essentialMonthly)} / {money(projection.spending.totalMonthly)}
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2 py-1.5">
                <dt className="text-gray-500 dark:text-gray-400">
                  {t("fire.assumptions_swr_plain")} ({t("fire.assumptions_swr_tech")}) / {t("fire.inflation")}
                </dt>
                <dd className="font-medium tabular-nums">
                  {s.safeWithdrawalRatePct}% / {s.expectedInflationPct}%
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2 py-1.5">
                <dt className="text-gray-500 dark:text-gray-400">{t("fire.tax_title")}</dt>
                <dd className="font-medium tabular-nums">
                  {s.applyCapitalGainsTax
                    ? `${formatPercent(Number(projection.gainFraction.percent), locale, 1)}${s.indexTaxBrackets ? ` · ${t("fire.indexed_short")}` : ""}`
                    : t("fire.tax_off_short")}
                </dd>
              </div>
            </dl>
            <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
              {realMode
                ? t("fire.real_note", { inflation: s.expectedInflationPct })
                : t("fire.nominal_note", { inflation: s.expectedInflationPct })}
            </p>
          </aside>
        </div>
      </CardBody>
    </Card>
  );
}
