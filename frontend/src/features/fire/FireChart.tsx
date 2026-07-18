import { Fragment, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Area, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { FireProjection } from "@/api/fire";
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

export function FireChart({ projection, currency }: { projection: FireProjection; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;

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

  const { chartData, yTicks } = useMemo(() => {
    const yearSet = new Set<number>();
    for (const s of projection.scenarios) for (const p of s.percentiles) yearSet.add(p.year);
    for (const tier of activeTiers) for (const point of tier.targetCurve) yearSet.add(point.year);

    let maxValue = 0;
    const rows = Array.from(yearSet)
      .sort((a, b) => a - b)
      .map((year) => {
        const row: Record<string, number | string> = { year };
        for (const s of projection.scenarios) {
          const p = s.percentiles.find((x) => x.year === year);
          if (p) {
            const k = scenarioKey(s);
            row[`${k}_p50`] = Number(p.p50);
            row[`${k}_p10`] = Number(p.p10);
            row[`${k}_p90`] = Number(p.p90);
            maxValue = Math.max(maxValue, Number(p.p90));
          }
        }
        for (const tier of activeTiers) {
          const point = tier.targetCurve.find((x) => x.year === year);
          if (point) {
            row[`target_${tier.key}`] = Number(point.value);
            maxValue = Math.max(maxValue, Number(point.value));
          }
        }
        return row;
      });
    return { chartData: rows, yTicks: niceMoneyTicks(maxValue) };
  }, [projection, activeTiers]);

  const s = projection.settings;
  const contributionMissing = projection.contributions.activeMonthly === null;
  const money = (v: string | number | null | undefined) => formatMoney(v ?? 0, currency, locale);

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">
            {t("fire.projection")} ({projection.monteCarloTrials.toLocaleString(locale)} {t("fire.trials")})
          </p>
          <ActualReturnBadge projection={projection} />
        </div>
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
                          const lo = formatMoney(String(v[0]), currency, locale);
                          const hi = formatMoney(String(v[1]), currency, locale);
                          return `${lo} – ${hi}`;
                        }
                        return formatMoney(String(v ?? "0"), currency, locale);
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
                {projection.scenarios.map((sc, idx) => {
                  const k = scenarioKey(sc);
                  const color = SCENARIO_PALETTE[idx % SCENARIO_PALETTE.length];
                  const label = scenarioLabel(sc, t);
                  const hidden = hiddenSeries.has(`${k}_p50`);
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
                        name={`${label} p10–p90`}
                        hide={hidden}
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
              </ComposedChart>
            </ResponsiveContainer>
          </div>

          {/* Assumptions block: the projection must be auditable at a glance. */}
          <aside className="rounded-md border border-border p-3 text-sm dark:border-gray-700">
            <p className="mb-2 font-medium">{t("fire.assumptions")}</p>
            <ul className="space-y-1 text-gray-600 dark:text-gray-300">
              <li>{t("fire.assumptions_swr", { value: s.safeWithdrawalRatePct })}</li>
              <li>{t("fire.assumptions_inflation", { value: s.expectedInflationPct })}</li>
              <li>{s.indexContribution ? t("fire.assumptions_indexed_on") : t("fire.assumptions_indexed_off")}</li>
              <li>
                {t("fire.assumptions_contribution", {
                  amount: money(projection.contributions.activeMonthly ?? "0"),
                  mode: t(`fire.contribution_${projection.contributions.mode}`),
                })}
              </li>
              <li>
                {projection.spending.essentialMode === "manual"
                  ? t("fire.assumptions_essential_manual", { amount: money(projection.spending.essentialMonthly) })
                  : t("fire.assumptions_essential_derived", {
                      amount: money(projection.spending.essentialMonthly),
                      months: projection.spending.monthsAvailable,
                    })}
              </li>
              <li>
                {projection.spending.totalMode === "manual"
                  ? t("fire.assumptions_total_manual", { amount: money(projection.spending.totalMonthly) })
                  : t("fire.assumptions_total_derived", {
                      amount: money(projection.spending.totalMonthly),
                      months: projection.spending.monthsAvailable,
                    })}
              </li>
              <li>
                {s.applyCapitalGainsTax
                  ? t("fire.assumptions_tax_on", { gain: formatPercent(Number(projection.gainFraction.percent), locale, 2) })
                  : t("fire.assumptions_tax_off")}
              </li>
              <li>
                {t("fire.assumptions_as_of", {
                  value: money(projection.startingValue),
                  date: projection.snapshotDate ?? "—",
                })}
              </li>
            </ul>
            <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.nominal_note", { inflation: s.expectedInflationPct })}
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
