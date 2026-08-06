import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useBenchmarks,
  useBenchmarkSeries,
  usePortfolioEvolution,
  usePortfolioSummary,
  type HoldingAssetClass,
} from "@/api/portfolio";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { RangeSelector } from "@/components/ui/RangeSelector";
import { defaultRange, resolveRange, type RangeValue } from "@/lib/range";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatCompactMoney, formatMoney, formatPercent } from "@/lib/money";
import { formatDate } from "@/lib/dates";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { benchmarkColors, benchmarkColumnsByDate } from "./benchmarkOverlay";

const VALUE_COLOR = "#0ea5e9";
const INVESTED_COLOR = "#6b7280";
const REALIZED_COLOR = "#22c55e";
const UNREALIZED_COLOR = "#a855f7";
const TWR_COLOR = "#d946ef";
const ZERO_LINE_COLOR = "#9ca3af";

// Both stacked panels share one synced cursor and align their plot areas by using the
// same left axis width, so their X axes line up under a single tick/format policy.
const SYNC_ID = "portfolio-evolution";
const AXIS_WIDTH = 56;

const ASSET_CLASSES: HoldingAssetClass[] = ["crypto", "etf", "stock", "fund"];

function toneClass(v: number): string {
  if (v > 0) return "text-green-600 dark:text-green-400";
  if (v < 0) return "text-red-600 dark:text-red-400";
  return "text-gray-500 dark:text-gray-400";
}

function StatCard({
  label,
  color,
  endText,
  startLabel,
  startText,
  deltaText,
  deltaTone,
}: {
  label: string;
  color: string;
  endText: string;
  startLabel: string;
  startText: string;
  deltaText: string;
  deltaTone: number;
}) {
  return (
    <div className="rounded-md border border-border p-2.5">
      <div className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400">
        <span className="inline-block h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: color }} />
        <span className="truncate">{label}</span>
      </div>
      <p className="mt-1 text-base font-semibold tabular-nums text-gray-900 dark:text-gray-100">{endText}</p>
      <p className={`mt-0.5 text-xs font-medium tabular-nums ${toneClass(deltaTone)}`}>{deltaText}</p>
      <p className="mt-0.5 text-xs tabular-nums text-gray-400 dark:text-gray-500">
        {startLabel}: {startText}
      </p>
    </div>
  );
}

export function PortfolioEvolutionTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: summary } = usePortfolioSummary(household.householdId);

  const [assetClass, setAssetClass] = useState<HoldingAssetClass | "">("");
  const [holdingId, setHoldingId] = useState("");
  const [range, setRange] = useState<RangeValue>(defaultRange("1y"));
  // Off by default: the chart is identical to today until the user opts a benchmark in.
  const [selectedBenchmarks, setSelectedBenchmarks] = useState<string[]>([]);

  const holdingOptions = useMemo(
    () =>
      (summary?.holdings ?? [])
        .map((row) => row.holding)
        .filter((h) => !assetClass || h.assetClass === assetClass),
    [summary, assetClass],
  );

  const filters = useMemo(() => {
    const { from, to } = resolveRange(range);
    return {
      from,
      to,
      assetClass: assetClass || undefined,
      holdingId: holdingId || undefined,
    };
  }, [range, assetClass, holdingId]);

  const { data: evolution } = usePortfolioEvolution(household.householdId, filters);

  const data = useMemo(
    () =>
      (evolution?.points ?? []).map((p) => ({
        date: p.date,
        value: Number(p.value),
        invested: Number(p.invested),
        realized: Number(p.realizedPnl),
        unrealized: Number(p.unrealizedPnl),
        // Fraction over the wire -> percentage points for the axis and tooltip.
        twr: Number(p.twrPct) * 100,
      })),
    [evolution],
  );

  const { data: benchmarks } = useBenchmarks(household.householdId);
  // Only hit the endpoint once the user opts in and there is a TWR curve to overlay onto.
  const { data: benchmarkSeries } = useBenchmarkSeries(
    household.householdId,
    filters,
    selectedBenchmarks,
    data.length > 0,
  );

  // Colour per benchmark, keyed by the registry order so a new benchmark is stable and
  // needs no code change here. A benchmark's label falls back to its key if untranslated.
  const benchmarkColor = useMemo(() => benchmarkColors((benchmarks ?? []).map((b) => b.key)), [benchmarks]);
  const benchmarkName = (key: string) => t(`portfolio.benchmark.${key}`, { defaultValue: key });

  const toggleBenchmark = (key: string) =>
    setSelectedBenchmarks((prev) =>
      prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key],
    );

  // Benchmark TWR points share the ROI curve's sample dates, so they merge onto the same rows.
  const benchByDate = useMemo(
    () => benchmarkColumnsByDate(benchmarkSeries?.series, selectedBenchmarks),
    [benchmarkSeries, selectedBenchmarks],
  );

  const roiData = useMemo(
    () => data.map((d) => ({ ...d, ...(benchByDate.get(d.date) ?? {}) })),
    [data, benchByDate],
  );

  const partialBenchmarks = useMemo(
    () =>
      (benchmarkSeries?.series ?? []).filter(
        (s) => selectedBenchmarks.includes(s.key) && s.partial && s.availableFrom,
      ),
    [benchmarkSeries, selectedBenchmarks],
  );

  // Re-anchor the summary to the selected range: first/last points are its bounds.
  const summaryRows = useMemo(() => {
    if (data.length === 0) return null;
    const first = data[0];
    const last = data[data.length - 1];
    const build = (start: number, end: number) => {
      const deltaAbs = end - start;
      const deltaPct = start !== 0 ? (deltaAbs / Math.abs(start)) * 100 : null;
      return { start, end, deltaAbs, deltaPct };
    };
    return {
      value: build(first.value, last.value),
      invested: build(first.invested, last.invested),
      realized: build(first.realized, last.realized),
      unrealized: build(first.unrealized, last.unrealized),
      twr: build(first.twr, last.twr),
    };
  }, [data]);

  // Adapt tick format to the span: short ranges show day/month, long ranges month/year.
  // `interval="preserveStartEnd"` + `minTickGap` let the chart drop labels to fit the width.
  const spanDays = useMemo(() => {
    if (data.length < 2) return 0;
    const first = new Date(data[0].date).getTime();
    const last = new Date(data[data.length - 1].date).getTime();
    return Math.round((last - first) / 86_400_000);
  }, [data]);
  const xPattern = spanDays > 92 ? "MMM yy" : "d MMM";
  const formatXTick = (value: string) => formatDate(value, i18n.language, xPattern);

  const formatEuroTick = (value: number) => formatCompactMoney(value, household.currency, i18n.language);
  const formatPctTick = (value: number) => formatPercent(value, i18n.language, 0);

  const money = (v: number) => formatMoney(v, household.currency, i18n.language);
  const signedMoney = (v: number) => `${v >= 0 ? "+" : ""}${money(v)}`;
  const signedPct = (v: number, digits = 1) => `${v >= 0 ? "+" : ""}${formatPercent(v, i18n.language, digits)}`;
  const moneyDelta = (deltaAbs: number, deltaPct: number | null) =>
    deltaPct === null ? signedMoney(deltaAbs) : `${signedMoney(deltaAbs)} · ${signedPct(deltaPct)}`;

  const valueCards = summaryRows
    ? [
        { label: t("portfolio.current_value"), color: VALUE_COLOR, row: summaryRows.value },
        { label: t("portfolio.invested"), color: INVESTED_COLOR, row: summaryRows.invested },
        { label: t("portfolio.realized_pnl"), color: REALIZED_COLOR, row: summaryRows.realized },
        { label: t("portfolio.unrealized_pnl"), color: UNREALIZED_COLOR, row: summaryRows.unrealized },
      ]
    : [];

  const xAxisProps = {
    dataKey: "date",
    tickFormatter: formatXTick,
    interval: "preserveStartEnd" as const,
    minTickGap: 40,
    tickMargin: 8,
    tick: { fontSize: 12 },
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("portfolio.evolution")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("portfolio.evolution_description")}</p>
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <div className="w-36">
            <Label>{t("portfolio.asset_class")}</Label>
            <Select
              value={assetClass}
              onChange={(e) => {
                setAssetClass(e.target.value as HoldingAssetClass | "");
                setHoldingId("");
              }}
            >
              <option value="">{t("common.all")}</option>
              {ASSET_CLASSES.map((c) => (
                <option key={c} value={c}>{t(`portfolio.class.${c}`)}</option>
              ))}
            </Select>
          </div>
          <div className="w-44">
            <Label>{t("portfolio.holdings")}</Label>
            <Select value={holdingId} onChange={(e) => setHoldingId(e.target.value)}>
              <option value="">{t("common.all")}</option>
              {holdingOptions.map((h) => (
                <option key={h.id} value={h.id}>
                  {h.symbol}{h.label ? ` — ${h.label}` : ""}
                </option>
              ))}
            </Select>
          </div>
          <RangeSelector value={range} onChange={setRange} />
        </div>
      </CardHeader>
      <CardBody className="space-y-5">
        {data.length === 0 || !summaryRows ? (
          <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
        ) : (
          <>
            <div>
              <p className="mb-2 text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
                {t("portfolio.period_summary")}
              </p>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                {valueCards.map((c) => (
                  <StatCard
                    key={c.label}
                    label={c.label}
                    color={c.color}
                    endText={money(c.row.end)}
                    startLabel={t("portfolio.start")}
                    startText={money(c.row.start)}
                    deltaText={moneyDelta(c.row.deltaAbs, c.row.deltaPct)}
                    deltaTone={c.row.deltaAbs}
                  />
                ))}
              </div>
            </div>

            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={data} syncId={SYNC_ID} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis {...xAxisProps} />
                  <YAxis width={AXIS_WIDTH} tickFormatter={formatEuroTick} tick={{ fontSize: 12 }} />
                  <Tooltip
                    content={(props) => (
                      <ChartTooltip {...props} formatValue={(v) => money(Number(v))} />
                    )}
                  />
                  <Legend />
                  <Area
                    type="monotone"
                    dataKey="value"
                    stroke={VALUE_COLOR}
                    fill={VALUE_COLOR}
                    fillOpacity={0.25}
                    name={t("portfolio.current_value")}
                  />
                  <Line
                    type="monotone"
                    dataKey="invested"
                    stroke={INVESTED_COLOR}
                    strokeWidth={1.5}
                    strokeDasharray="4 4"
                    dot={false}
                    name={t("portfolio.invested")}
                  />
                  <Line
                    type="monotone"
                    dataKey="unrealized"
                    stroke={UNREALIZED_COLOR}
                    strokeWidth={1.5}
                    dot={false}
                    name={t("portfolio.unrealized_pnl")}
                  />
                  <Line
                    type="monotone"
                    dataKey="realized"
                    stroke={REALIZED_COLOR}
                    strokeWidth={1.5}
                    dot={false}
                    name={t("portfolio.realized_pnl")}
                  />
                </ComposedChart>
              </ResponsiveContainer>
            </div>

            <div className="border-t border-border pt-4">
              <p className="font-medium">{t("portfolio.roi_twr")} %</p>
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("portfolio.roi_twr_description")}</p>
              <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
                <StatCard
                  label={t("portfolio.twr")}
                  color={TWR_COLOR}
                  endText={formatPercent(summaryRows.twr.end, i18n.language, 1)}
                  startLabel={t("portfolio.start")}
                  startText={formatPercent(summaryRows.twr.start, i18n.language, 1)}
                  deltaText={signedPct(summaryRows.twr.deltaAbs)}
                  deltaTone={summaryRows.twr.deltaAbs}
                />
              </div>

              {benchmarks && benchmarks.length > 0 && (
                <div className="mt-3">
                  <Label>{t("portfolio.benchmarks_label")}</Label>
                  <div className="mt-1 flex flex-wrap gap-2">
                    {benchmarks.map((b) => {
                      const active = selectedBenchmarks.includes(b.key);
                      return (
                        <button
                          key={b.key}
                          type="button"
                          disabled={!b.hasData}
                          onClick={() => toggleBenchmark(b.key)}
                          title={b.hasData ? undefined : t("portfolio.benchmark_no_data")}
                          className={[
                            "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-sm transition-colors",
                            !b.hasData
                              ? "cursor-not-allowed border-border bg-gray-50 text-gray-400 dark:bg-chip dark:text-gray-500"
                              : active
                                ? "border-primary bg-sky-50 text-primary dark:bg-sky-900/40 dark:text-sky-300"
                                : "border-border-strong bg-raised text-gray-700 hover:bg-raised-hover dark:text-gray-200",
                          ].join(" ")}
                        >
                          <span
                            className="inline-block h-2 w-2 shrink-0 rounded-full"
                            style={{ backgroundColor: benchmarkColor[b.key] }}
                          />
                          {benchmarkName(b.key)}
                        </button>
                      );
                    })}
                  </div>
                  <p className="mt-1.5 text-xs text-gray-500 dark:text-gray-400">
                    {t("portfolio.benchmark_eur_note")}
                  </p>
                </div>
              )}

              <div className={selectedBenchmarks.length > 0 ? "mt-3 h-56" : "mt-3 h-44"}>
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={roiData} syncId={SYNC_ID} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis {...xAxisProps} />
                    <YAxis width={AXIS_WIDTH} tickFormatter={formatPctTick} tick={{ fontSize: 12 }} />
                    <ReferenceLine y={0} stroke={ZERO_LINE_COLOR} strokeWidth={1} />
                    <Tooltip
                      content={(props) => (
                        <ChartTooltip {...props} formatValue={(v) => formatPercent(Number(v), i18n.language, 2)} />
                      )}
                    />
                    {selectedBenchmarks.length > 0 && <Legend />}
                    <Line
                      type="monotone"
                      dataKey="twr"
                      stroke={TWR_COLOR}
                      strokeWidth={2}
                      dot={false}
                      name={t("portfolio.twr")}
                    />
                    {selectedBenchmarks.map((key) => (
                      <Line
                        key={key}
                        type="monotone"
                        dataKey={`bench_${key}`}
                        stroke={benchmarkColor[key] ?? ZERO_LINE_COLOR}
                        strokeWidth={1.5}
                        strokeDasharray="5 3"
                        dot={false}
                        connectNulls={false}
                        name={benchmarkName(key)}
                      />
                    ))}
                  </LineChart>
                </ResponsiveContainer>
              </div>

              {partialBenchmarks.length > 0 && (
                <p className="mt-2 text-xs text-amber-600 dark:text-amber-400">
                  {t("portfolio.benchmark_partial_note")}
                </p>
              )}
            </div>
          </>
        )}
      </CardBody>
    </Card>
  );
}
