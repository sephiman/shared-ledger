import { Fragment, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Area, CartesianGrid, ComposedChart, Legend, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { FireProjection, FireTierKey } from "@/api/fire";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { formatCompactMoney, formatMoney, formatPercent } from "@/lib/money";
import { Explain } from "./Explain";
import { niceMoneyTicks } from "./fireTicks";
import { TIER_COLORS, scenarioLabel } from "./fireLabels";

const SCENARIO_PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444"];

function scenarioKey(s: { meanPercent: string; stdDevPercent: string }): string {
  return `${s.meanPercent}_${s.stdDevPercent}`;
}

export function FireChart({
  projection,
  baselineProjection,
  currency,
  whatIfOpen,
  onToggleWhatIf,
}: {
  projection: FireProjection;
  baselineProjection?: FireProjection;
  currency: string;
  whatIfOpen?: boolean;
  onToggleWhatIf?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;

  const [realMode, setRealMode] = useState(false);
  const [chartView, setChartView] = useState<"accumulation" | "drawdown">("accumulation");
  const defaultBandIdx = useMemo(() => {
    const nonHistIdx = projection.scenarios
      .map((s, i) => ({ s, i }))
      .filter(({ s }) => !s.historical);
    if (nonHistIdx.length > 0) return String(nonHistIdx[Math.floor(nonHistIdx.length / 2)].i);
    return projection.scenarios.length > 0 ? "0" : "all";
  }, [projection.scenarios]);
  const [bandFocus, setBandFocus] = useState<string>(defaultBandIdx);

  const drawdownTiers = useMemo(() => {
    const focused = projection.scenarios[Number(bandFocus)] ?? projection.scenarios[Number(defaultBandIdx)] ?? projection.scenarios[0];
    return focused?.tierStats.filter((st) => st.drawdownPercentiles.length > 0).map((st) => st.tier) ?? [];
  }, [projection.scenarios, bandFocus, defaultBandIdx]);
  const [drawdownTier, setDrawdownTier] = useState<FireTierKey>("fire");
  const activeDrawdownTier = drawdownTiers.includes(drawdownTier) ? drawdownTier : drawdownTiers[0] ?? "fire";

  // Clicking a legend entry hides/shows its series, keyed by the series dataKey.
  const [hiddenSeries, setHiddenSeries] = useState<Set<string>>(new Set());
  function toggleSeries(dataKey: unknown) {
    if (typeof dataKey !== "string") return;
    setHiddenSeries((prev) => {
      const next = new Set(prev);
      if (next.has(dataKey)) next.delete(dataKey);
      else next.add(dataKey);
      return next;
    });
  }

  const activeTiers = projection.tiers.filter((tier) => tier.enabled && tier.targetToday !== null);
  const hasHistoricalWealth = projection.historicalWealth.length > 0;
  const hasCumulativeContribs = projection.cumulativeContributions.length > 0;

  const { chartData, yTicks, minYear, maxYear } = useMemo(() => {
    const inflation = Number(projection.settings.expectedInflationPct) / 100;
    if (chartView === "drawdown") {
      const scIdx = bandFocus === "all" ? Number(defaultBandIdx) : Number(bandFocus);
      const sc = projection.scenarios[scIdx] ?? projection.scenarios[0];
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
    for (const c of projection.cumulativeContributions) yearSet.add(c.year);
    for (const s of projection.scenarios) for (const p of s.percentiles) yearSet.add(p.year);
    for (const tier of activeTiers) for (const point of tier.targetCurve) yearSet.add(point.year);

    const deflatorFor = (year: number) =>
      realMode && year > projection.startYear ? (1 + inflation) ** (year - projection.startYear) : 1;

    const baseScIdx = bandFocus === "all" ? Number(defaultBandIdx) : Number(bandFocus);
    const baseScenario = baselineProjection?.scenarios[baseScIdx] ?? baselineProjection?.scenarios[0];

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
      const contrib = projection.cumulativeContributions.find((x) => x.year === year);
      if (contrib) {
        row.cumulative_contributions = Number(contrib.cumulative);
        maxValue = Math.max(maxValue, row.cumulative_contributions);
      }
      for (const s of projection.scenarios) {
        const p = s.percentiles.find((x) => x.year === year);
        if (p) {
          const k = scenarioKey(s);
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
      for (const tier of activeTiers) {
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
  }, [projection, baselineProjection, activeTiers, realMode, chartView, bandFocus, defaultBandIdx, activeDrawdownTier]);

  const s = projection.settings;
  const contributionMissing = projection.contributions.activeMonthly === null;
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);

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
            {onToggleWhatIf && (
              <button
                type="button"
                onClick={onToggleWhatIf}
                className={`rounded-md border px-2.5 py-1 text-xs font-medium ${whatIfOpen ? "border-sky-400 bg-sky-50 text-sky-700 dark:border-sky-600 dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-700 hover:bg-surface dark:text-gray-200"}`}
              >
                {t("fire.what_if_title")}
              </button>
            )}
          </div>
          <ActualReturnBadge projection={projection} />
        </div>
        {projection.scenarios.length > 1 && (
          <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
            <span className="text-gray-500 dark:text-gray-400">{t("fire.band_label")}:</span>
            {projection.scenarios.map((sc, idx) => {
              const active = bandFocus === String(idx);
              return (
                <button
                  key={scenarioKey(sc)}
                  type="button"
                  onClick={() => setBandFocus(String(idx))}
                  className={`rounded-full border px-2.5 py-0.5 font-medium ${active ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-600 hover:text-gray-800 dark:text-gray-300"}`}
                >
                  {scenarioLabel(sc, t)}
                </button>
              );
            })}
            <button
              type="button"
              onClick={() => setBandFocus("all")}
              className={`rounded-full border px-2.5 py-0.5 font-medium ${bandFocus === "all" ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300" : "border-border bg-raised text-gray-600 hover:text-gray-800 dark:text-gray-300"}`}
            >
              {t("fire.band_all")}
            </button>
            {chartView === "drawdown" && drawdownTiers.length > 0 && (
              <>
                <span className="ml-2 text-gray-500 dark:text-gray-400">{t("fire.tier")}:</span>
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
              </>
            )}
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
          <div className="h-96 lg:col-span-2">
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
                <Legend
                  onClick={(entry) => toggleSeries((entry as { dataKey?: unknown }).dataKey)}
                  wrapperStyle={{ cursor: "pointer" }}
                  inactiveColor="#9ca3af"
                  formatter={(value, entry) => (
                    <span style={(entry as { inactive?: boolean }).inactive ? { opacity: 0.5, textDecoration: "line-through" } : undefined}>
                      {value}
                    </span>
                  )}
                />
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
                      name="p10–p90"
                    />
                    <Line
                      type="monotone"
                      dataKey="dd_p50"
                      name={`${t(`fire.tier_${activeDrawdownTier}`)} (p50)`}
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
                        hide={hiddenSeries.has("historical_wealth")}
                      />
                    )}
                    {hasCumulativeContribs && (
                      <Line
                        type="monotone"
                        dataKey="cumulative_contributions"
                        name={t("fire.cumulative_contributions")}
                        stroke="#14b8a6"
                        strokeWidth={1.5}
                        strokeDasharray="4 4"
                        dot={{ r: 2 }}
                        connectNulls={false}
                        hide={hiddenSeries.has("cumulative_contributions")}
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
                        hide={hiddenSeries.has("baseline_p50")}
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
                    {projection.scenarios.map((sc, idx) => {
                      const k = scenarioKey(sc);
                      const color = SCENARIO_PALETTE[idx % SCENARIO_PALETTE.length];
                      const label = scenarioLabel(sc, t);
                      const hidden = hiddenSeries.has(`${k}_p50`);
                      const showBand = !hidden && (bandFocus === "all" || bandFocus === String(idx));
                      return (
                        <Fragment key={k}>
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
                            name={`${label} p10–p90`}
                            hide={!showBand}
                          />
                          <Line type="monotone" dataKey={`${k}_p50`} name={label} stroke={color} strokeWidth={2} dot={false} hide={hidden} />
                        </Fragment>
                      );
                    })}
                    {activeTiers.map((tier) => (
                      <Line
                        key={tier.key}
                        type="monotone"
                        dataKey={`target_${tier.key}`}
                        name={tier.key === "custom" ? t("fire.tier_custom") : t("fire.tier_target_name", { tier: t(`fire.tier_${tier.key}`) })}
                        stroke={TIER_COLORS[tier.key]}
                        strokeWidth={1.5}
                        strokeDasharray="6 3"
                        dot={false}
                        hide={hiddenSeries.has(`target_${tier.key}`)}
                      />
                    ))}
                  </>
                )}
              </ComposedChart>
            </ResponsiveContainer>
          </div>

          {/* Assumptions block: structured key-value summary for quick scanning. */}
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
                <dt className="text-gray-500 dark:text-gray-400">{t("fire.monthly_contribution")}</dt>
                <dd className="text-right font-medium tabular-nums">
                  {money(projection.contributions.activeMonthly ?? "0")}
                  <span className="ml-1 font-normal text-gray-500">
                    ({t(`fire.contribution_${projection.contributions.mode}`)}
                    {s.indexContribution ? ` · ${t("fire.indexed_short")}` : ""})
                  </span>
                </dd>
              </div>
              {projection.contributions.savingsRatePct !== null && (
                <div className="flex items-baseline justify-between gap-2 py-1.5">
                  <dt className="text-gray-500 dark:text-gray-400">{t("fire.savings_rate_label")}</dt>
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
                <dt className="text-gray-500 dark:text-gray-400">{t("fire.assumptions_swr_inf_label")}</dt>
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

function ActualReturnBadge({ projection }: { projection: FireProjection }) {
  const { t } = useTranslation();
  if (projection.actualReturn) {
    const ar = projection.actualReturn;
    const partial = ar.uncoveredMonths > 0;
    return (
      <div className="max-w-xs text-right text-sm text-gray-600 dark:text-gray-300">
        <p>
          {t("fire.actual_return")}: <span className="font-medium">{ar.annualizedPercent}%</span>
        </p>
        {partial && (
          <p className="text-xs text-amber-700 dark:text-amber-300">
            {t("fire.actual_return_coverage_warning", { months: ar.uncoveredMonths })}
          </p>
        )}
        <Explain>
          <p>
            {t("fire.actual_return_explain", {
              from: ar.fromDate,
              to: ar.toDate,
              movements: ar.movementCount,
            })}
          </p>
          {partial && <p>{t("fire.actual_return_gap_explain", { first: ar.firstMovementDate, months: ar.uncoveredMonths })}</p>}
        </Explain>
      </div>
    );
  }
  const reason = projection.actualReturnUnavailableReason ?? "not_computable";
  return <p className="max-w-xs text-right text-sm text-gray-500 dark:text-gray-400">{t(`fire.no_return_${reason}`)}</p>;
}
