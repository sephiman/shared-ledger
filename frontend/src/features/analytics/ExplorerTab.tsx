import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useExplorer, useHeatmap, type ExplorerResponse, type HeatmapResponse } from "@/api/analytics";
import { useCategories, type Category } from "@/api/catalog";
import { Card, CardBody, CardHeader, CheckboxTree, Label, Select, type CheckboxTreeGroup } from "@/components/ui/primitives";
import {
  RangeSelector,
  defaultRange,
  rangeToMonths,
  resolveRange,
  type RangeValue,
} from "@/components/ui/RangeSelector";
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatCompactMoney, formatMoney, formatNumber } from "@/lib/money";
import { monthName } from "@/lib/dates";
import { categoryIcon, groupIcon } from "@/lib/categoryGroup";
import { categoryLabel } from "@/lib/categoryLabel";
import { ChartTooltip } from "@/components/charts/ChartTooltip";

type Range = "12" | "24" | "all";

const CURRENT_COLOR = "#0ea5e9";
const PRIOR_COLOR = "#94a3b8";

const TOP_DESC_DEFAULT_LIMIT = 10;

interface ScopeValue {
  type: "group" | "category";
  code: string;
}

function encodeScope(s: ScopeValue): string {
  return `${s.type}:${s.code}`;
}

function decodeScope(v: string): ScopeValue | null {
  const i = v.indexOf(":");
  if (i < 0) return null;
  const type = v.slice(0, i);
  const code = v.slice(i + 1);
  if (type !== "group" && type !== "category") return null;
  return { type, code };
}

export function ExplorerTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: categories = [] } = useCategories(household.householdId);
  const [scope, setScope] = useState<ScopeValue | null>(null);
  const [range, setRange] = useState<Range>("12");
  const [yoyOverlay, setYoyOverlay] = useState<boolean>(true);
  const [showAllDescriptions, setShowAllDescriptions] = useState<boolean>(false);

  const months = range === "all" ? 9999 : Number(range);

  const { data, isLoading } = useExplorer(household.householdId, {
    scopeType: scope?.type,
    scopeCode: scope?.code,
    months,
    yoyOverlay,
  });

  // Adopt the server-resolved scope on first load so the dropdown reflects the default.
  useEffect(() => {
    if (scope == null && data) {
      setScope({ type: data.scopeType, code: data.scopeCode });
    }
  }, [data, scope]);

  // Hide the toggle entirely when there's not enough prior data to overlay.
  const yoyAvailable = (data?.priorYearsAvailable ?? 0) >= 1;
  useEffect(() => {
    if (!yoyAvailable && yoyOverlay) setYoyOverlay(false);
  }, [yoyAvailable, yoyOverlay]);

  const scopeOptions = useMemo(() => buildScopeOptions(categories, t), [categories, t]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.explorer")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.explorer_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <div>
              <Label>{t("analytics.explorer_scope")}</Label>
              <Select
                value={scope ? encodeScope(scope) : ""}
                onChange={(e) => {
                  const next = decodeScope(e.target.value);
                  if (next) setScope(next);
                  setShowAllDescriptions(false);
                }}
              >
                {!scope && <option value="">{t("common.loading")}</option>}
                {scopeOptions.groups.map((g) => (
                  <option key={`group:${g.code}`} value={`group:${g.code}`}>
                    {`${g.label} ${groupIcon(g.code)}`}
                  </option>
                ))}
                <option disabled value="">────────</option>
                {scopeOptions.categories.map((c) => (
                  <option key={`category:${c.code}`} value={`category:${c.code}`}>
                    {`${c.label} ${categoryIcon(c.code)}`}
                  </option>
                ))}
              </Select>
            </div>
            <div>
              <Label>{t("analytics.explorer_range")}</Label>
              <Select value={range} onChange={(e) => setRange(e.target.value as Range)}>
                <option value="12">{t("analytics.range_12")}</option>
                <option value="24">{t("analytics.range_24")}</option>
                <option value="all">{t("analytics.range_all")}</option>
              </Select>
            </div>
            {yoyAvailable && (
              <div>
                <Label>{t("analytics.explorer_yoy")}</Label>
                <label className="mt-2 inline-flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={yoyOverlay}
                    onChange={(e) => setYoyOverlay(e.target.checked)}
                    className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                  />
                  <span>{t("analytics.explorer_yoy_help")}</span>
                </label>
              </div>
            )}
          </div>

          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && (
            <ExplorerBody
              data={data}
              currency={household.currency}
              locale={i18n.language}
              categories={categories}
              showAllDescriptions={showAllDescriptions}
              onToggleDescriptions={() => setShowAllDescriptions((v) => !v)}
            />
          )}
        </CardBody>
      </Card>

      <StackedExplorerPanel
        householdId={household.householdId}
        categories={categories}
        currency={household.currency}
        locale={i18n.language}
      />
    </div>
  );
}

function buildScopeOptions(
  categories: Category[],
  t: ReturnType<typeof useTranslation>["t"],
): {
  groups: { code: string; label: string }[];
  categories: { code: string; label: string }[];
} {
  const expenseCats = categories.filter((c) => c.kind === "expense");
  const seen = new Set<string>();
  const groups: { code: string; label: string }[] = [];
  for (const c of expenseCats) {
    const g = c.group ?? "ungrouped";
    if (seen.has(g)) continue;
    seen.add(g);
    groups.push({ code: g, label: t(`category_group.${g}`) });
  }
  groups.sort((a, b) => a.label.localeCompare(b.label));
  const sortedCats = [...expenseCats]
    .sort((a, b) => {
      const ga = a.group ?? "ungrouped";
      const gb = b.group ?? "ungrouped";
      if (ga !== gb) return ga.localeCompare(gb);
      return a.sortOrder - b.sortOrder;
    })
    .map((c) => ({ code: c.code, label: categoryLabel(c, t) }));
  return { groups, categories: sortedCats };
}

function ExplorerBody({
  data,
  currency,
  locale,
  categories,
  showAllDescriptions,
  onToggleDescriptions,
}: {
  data: ExplorerResponse;
  currency: string;
  locale: string;
  categories: Category[];
  showAllDescriptions: boolean;
  onToggleDescriptions: () => void;
}) {
  const { t } = useTranslation();

  const chartData = useMemo(() => {
    return data.months.map((m, idx) => {
      const prior = data.priorMonths?.[idx];
      return {
        key: `${m.year}-${m.month}`,
        period: `${monthName(m.month, locale, "short")} ${String(m.year).slice(2)}`,
        current: Number(m.amount),
        prior: prior ? Number(prior.amount) : null,
        currentMonthLabel: `${monthName(m.month, locale, "long")} ${m.year}`,
        priorMonthLabel: prior ? `${monthName(prior.month, locale, "long")} ${prior.year}` : null,
      };
    });
  }, [data, locale]);

  const scopeLabel = useMemo(() => {
    if (data.scopeType === "group") {
      return `${groupIcon(data.scopeCode)} ${t(`category_group.${data.scopeCode}`)}`;
    }
    const found = categories.find((c) => c.code === data.scopeCode);
    const name = found ? categoryLabel(found, t) : t(`category.${data.scopeCode}`);
    return `${categoryIcon(data.scopeCode)} ${name}`;
  }, [data.scopeType, data.scopeCode, categories, t]);

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat
          label={t("analytics.explorer_avg_per_month")}
          value={formatMoney(data.averagePerMonth, currency, locale)}
        />
        <Stat
          label={t("analytics.explorer_median")}
          value={formatMoney(data.medianPerMonth, currency, locale)}
        />
        <Stat
          label={t("analytics.explorer_highest")}
          value={
            data.highestMonth
              ? formatMoney(data.highestMonth.amount, currency, locale)
              : "—"
          }
          sub={
            data.highestMonth
              ? `${monthName(data.highestMonth.month, locale, "short")} ${data.highestMonth.year}`
              : undefined
          }
        />
        <Stat
          label={t("analytics.explorer_lowest")}
          value={
            data.lowestNonZeroMonth
              ? formatMoney(data.lowestNonZeroMonth.amount, currency, locale)
              : "—"
          }
          sub={
            data.lowestNonZeroMonth
              ? `${monthName(data.lowestNonZeroMonth.month, locale, "short")} ${data.lowestNonZeroMonth.year}`
              : undefined
          }
        />
      </div>

      <p className="text-sm text-gray-600 dark:text-gray-300">
        {t("analytics.explorer_scope_label", { scope: scopeLabel })}
      </p>

      <div className="h-72">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="period" />
            <YAxis />
            <Tooltip
              content={(props) => (
                <ExplorerTooltip
                  payload={props.payload}
                  currency={currency}
                  locale={locale}
                />
              )}
            />
            <Bar
              dataKey="current"
              name={t("analytics.explorer_current")}
              fill={CURRENT_COLOR}
              maxBarSize={36}
            />
            {data.priorMonths && (
              <Line
                type="monotone"
                dataKey="prior"
                name={t("analytics.explorer_prior_year")}
                stroke={PRIOR_COLOR}
                strokeWidth={2}
                dot={false}
                connectNulls
              />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </div>

      {data.scopeType === "category" && data.topDescriptions && (
        <TopDescriptionsPanel
          rows={data.topDescriptions}
          currency={currency}
          locale={locale}
          showAll={showAllDescriptions}
          onToggle={onToggleDescriptions}
        />
      )}

      {data.scopeType === "group" && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          {t("analytics.explorer_descriptions_hidden_for_group")}
        </p>
      )}
    </div>
  );
}

// Distinct, reused-in-order palette for stacked bands. First entry matches the
// single-category panel's accent so the two panels feel like one family.
const STACK_COLORS = [
  "#0ea5e9", // sky
  "#f59e0b", // amber
  "#22c55e", // green
  "#a855f7", // purple
  "#ef4444", // red
  "#14b8a6", // teal
  "#eab308", // yellow
  "#ec4899", // pink
  "#6366f1", // indigo
  "#84cc16", // lime
  "#f97316", // orange
  "#06b6d4", // cyan
];
const STACK_TOTAL_COLOR = "#64748b"; // slate

const TOTAL_DATA_KEY = "__total";

function ymKey(year: number, month: number): number {
  return year * 12 + (month - 1);
}

function isoMonthKey(iso: string): number {
  return Number(iso.slice(0, 4)) * 12 + (Number(iso.slice(5, 7)) - 1);
}

interface StackBand {
  key: string;
  scope: ScopeValue;
  label: string;
  color: string;
}

/** Expense catalog as a group → categories tree, ordered like the single-category scope picker. */
function buildScopeTree(
  categories: Category[],
  t: ReturnType<typeof useTranslation>["t"],
): { code: string; label: string; categories: { code: string; label: string }[] }[] {
  const byGroup = new Map<string, { code: string; label: string }[]>();
  const sorted = [...categories.filter((c) => c.kind === "expense")].sort((a, b) => {
    const ga = a.group ?? "ungrouped";
    const gb = b.group ?? "ungrouped";
    if (ga !== gb) return ga.localeCompare(gb);
    return a.sortOrder - b.sortOrder;
  });
  for (const c of sorted) {
    const g = c.group ?? "ungrouped";
    const list = byGroup.get(g) ?? [];
    list.push({ code: c.code, label: categoryLabel(c, t) });
    byGroup.set(g, list);
  }
  return [...byGroup.entries()]
    .map(([code, cats]) => ({ code, label: t(`category_group.${code}`), categories: cats }))
    .sort((a, b) => a.label.localeCompare(b.label));
}

function StackedExplorerPanel({
  householdId,
  categories,
  currency,
  locale,
}: {
  householdId: string;
  categories: Category[];
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  const [selection, setSelection] = useState<string[]>([]);
  const [range, setRange] = useState<RangeValue>(defaultRange("1y"));
  const [showTotal, setShowTotal] = useState(true);

  const months = rangeToMonths(range);
  const { data: heatmap, isLoading } = useHeatmap(householdId, months, "expense");

  const tree = useMemo(() => buildScopeTree(categories, t), [categories, t]);

  // Tree checkbox states. Per group the selection holds EITHER the group band
  // (`group:code`) OR some of its `category:code`s — never both — so a group reads
  // as checked (band), indeterminate (some categories), or unchecked.
  const treeGroups: CheckboxTreeGroup[] = useMemo(() => {
    const selectedSet = new Set(selection);
    return tree.map((g) => {
      const groupChecked = selectedSet.has(encodeScope({ type: "group", code: g.code }));
      const someChild = g.categories.some((c) => selectedSet.has(encodeScope({ type: "category", code: c.code })));
      return {
        value: g.code,
        label: `${groupIcon(g.code)} ${g.label}`,
        checked: groupChecked,
        indeterminate: !groupChecked && someChild,
        children: g.categories.map((c) => ({
          value: c.code,
          label: `${categoryIcon(c.code)} ${c.label}`,
          checked: selectedSet.has(encodeScope({ type: "category", code: c.code })),
        })),
      };
    });
  }, [tree, selection]);

  const toggleGroup = (groupCode: string) => {
    const groupEnc = encodeScope({ type: "group", code: groupCode });
    setSelection((prev) => {
      if (prev.includes(groupEnc)) return prev.filter((v) => v !== groupEnc);
      // Selecting the group as one band clears any of its individual category bands.
      const childEncs = new Set(
        (tree.find((g) => g.code === groupCode)?.categories ?? []).map((c) =>
          encodeScope({ type: "category", code: c.code }),
        ),
      );
      return [...prev.filter((v) => !childEncs.has(v)), groupEnc];
    });
  };

  const toggleCategory = (groupCode: string, catCode: string) => {
    const catEnc = encodeScope({ type: "category", code: catCode });
    const groupEnc = encodeScope({ type: "group", code: groupCode });
    setSelection((prev) => {
      if (prev.includes(catEnc)) return prev.filter((v) => v !== catEnc);
      // Picking a category breaks the group band down into individual bands.
      return [...prev.filter((v) => v !== groupEnc), catEnc];
    });
  };

  const { chartData, bands, total, monthsCount } = useMemo(() => {
    const empty = { chartData: [] as Record<string, number | string>[], bands: [] as StackBand[], total: 0, monthsCount: 0 };
    if (!heatmap) return empty;

    const bounds = resolveRange(range);
    const fromKey = bounds.from ? isoMonthKey(bounds.from) : -Infinity;
    const toKey = bounds.to ? isoMonthKey(bounds.to) : Infinity;
    const keptIdx = heatmap.months
      .map((m, i) => ({ i, k: ymKey(m.year, m.month) }))
      .filter(({ k }) => k >= fromKey && k <= toKey)
      .map(({ i }) => i);

    const catValues = new Map<string, (string | null)[]>();
    const catsByGroup = new Map<string, string[]>();
    for (const row of heatmap.categories) {
      catValues.set(row.categoryCode, row.values);
      const g = row.groupCode ?? "ungrouped";
      const list = catsByGroup.get(g);
      if (list) list.push(row.categoryCode);
      else catsByGroup.set(g, [row.categoryCode]);
    }

    const bands: StackBand[] = selection
      .map((enc, idx): StackBand | null => {
        const scope = decodeScope(enc);
        if (!scope) return null;
        const color = STACK_COLORS[idx % STACK_COLORS.length];
        let label: string;
        if (scope.type === "group") {
          label = `${groupIcon(scope.code)} ${t(`category_group.${scope.code}`)}`;
        } else {
          const found = categories.find((c) => c.code === scope.code);
          label = `${categoryIcon(scope.code)} ${found ? categoryLabel(found, t) : t(`category.${scope.code}`)}`;
        }
        return { key: enc, scope, label, color };
      })
      .filter((b): b is StackBand => b !== null);

    const bandAmount = (scope: ScopeValue, i: number): number => {
      if (scope.type === "category") return Number(catValues.get(scope.code)?.[i] ?? 0);
      let sum = 0;
      for (const code of catsByGroup.get(scope.code) ?? []) sum += Number(catValues.get(code)?.[i] ?? 0);
      return sum;
    };

    let total = 0;
    const chartData = keptIdx.map((i) => {
      const m = heatmap.months[i];
      const row: Record<string, number | string> = {
        period: `${monthName(m.month, locale, "short")} ${String(m.year).slice(2)}`,
      };
      let rowTotal = 0;
      for (const b of bands) {
        const amt = bandAmount(b.scope, i);
        row[b.key] = amt;
        rowTotal += amt;
      }
      row[TOTAL_DATA_KEY] = rowTotal;
      total += rowTotal;
      return row;
    });

    return { chartData, bands, total, monthsCount: keptIdx.length };
  }, [heatmap, range, selection, categories, locale, t]);

  const avgPerMonth = monthsCount > 0 ? total / monthsCount : 0;
  const hasSelection = bands.length > 0;

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("analytics.stacked_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.stacked_description")}</p>
      </CardHeader>
      <CardBody>
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,18rem)_1fr]">
          {/* Selector: always-visible checkbox tree */}
          <div className="space-y-2">
            <Label className="mb-0">{t("analytics.stacked_selection")}</Label>
            <CheckboxTree groups={treeGroups} onToggleGroup={toggleGroup} onToggleLeaf={toggleCategory} />
            <div className="flex items-center justify-between px-0.5 text-xs text-gray-500 dark:text-gray-400">
              <span>{t("analytics.stacked_selected_count", { count: selection.length })}</span>
              <button
                type="button"
                onClick={() => setSelection([])}
                disabled={selection.length === 0}
                className="font-medium text-primary hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-40 disabled:no-underline"
              >
                {t("analytics.stacked_clear")}
              </button>
            </div>
          </div>

          {/* Chart, range and summary */}
          <div className="space-y-4">
            <div className="flex flex-wrap items-end gap-3">
              <RangeSelector value={range} onChange={setRange} />
              <div>
                <Label>{t("analytics.stacked_total_line")}</Label>
                <label className="mt-2 inline-flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={showTotal}
                    onChange={(e) => setShowTotal(e.target.checked)}
                    className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
                  />
                  <span>{t("analytics.stacked_total_line_help")}</span>
                </label>
              </div>
            </div>

            {!hasSelection && (
              <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.stacked_empty_selection")}</p>
            )}

            {hasSelection && (
              <>
                <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                  <Stat label={t("analytics.stacked_total_spent")} value={formatMoney(total, currency, locale)} />
                  <Stat label={t("analytics.stacked_avg_per_month")} value={formatMoney(avgPerMonth, currency, locale)} />
                </div>

                {isLoading && !heatmap ? (
                  <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
                ) : chartData.length === 0 ? (
                  <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
                ) : (
                  <div className="h-80">
                    <ResponsiveContainer width="100%" height="100%">
                      <ComposedChart data={chartData} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey="period" interval="preserveStartEnd" minTickGap={24} tick={{ fontSize: 12 }} />
                        <YAxis
                          width={56}
                          tick={{ fontSize: 12 }}
                          tickFormatter={(v) => formatCompactMoney(Number(v), currency, locale)}
                        />
                        <Tooltip
                          content={(props) => (
                            <ChartTooltip {...props} formatValue={(v) => formatMoney(Number(v), currency, locale)} />
                          )}
                        />
                        <Legend />
                        {bands.map((b) => (
                          <Bar key={b.key} dataKey={b.key} stackId="stack" name={b.label} fill={b.color} maxBarSize={48} />
                        ))}
                        {showTotal && (
                          <Line
                            type="monotone"
                            dataKey={TOTAL_DATA_KEY}
                            name={t("analytics.stacked_total")}
                            stroke={STACK_TOTAL_COLOR}
                            strokeWidth={2}
                            dot={false}
                          />
                        )}
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </CardBody>
    </Card>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-md border border-border p-3 dark:border-gray-700">
      <p className="text-xs uppercase tracking-wide text-gray-500 dark:text-gray-400">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
      {sub && <p className="text-xs text-gray-500 dark:text-gray-400">{sub}</p>}
    </div>
  );
}

interface TooltipPayloadItem {
  payload?: {
    currentMonthLabel?: string;
    priorMonthLabel?: string | null;
    current?: number;
    prior?: number | null;
  };
}

function ExplorerTooltip({
  payload,
  currency,
  locale,
}: {
  payload?: readonly TooltipPayloadItem[];
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  const first = payload?.[0]?.payload;
  if (!first) return null;
  const currentVal = first.current ?? 0;
  const priorVal = first.prior;
  const deltaAbs = priorVal != null ? currentVal - priorVal : null;
  const deltaPct = priorVal != null && priorVal !== 0 ? ((currentVal - priorVal) / priorVal) * 100 : null;
  return (
    <div className="rounded-md border border-border bg-white px-3 py-2 text-xs shadow-sm dark:bg-gray-800 dark:border-gray-600">
      <p className="font-medium">{first.currentMonthLabel}</p>
      <p className="mt-1">
        <span style={{ color: CURRENT_COLOR }}>●</span>{" "}
        {t("analytics.explorer_current")}: {formatMoney(currentVal, currency, locale)}
      </p>
      {priorVal != null && (
        <>
          <p>
            <span style={{ color: PRIOR_COLOR }}>●</span>{" "}
            {first.priorMonthLabel}: {formatMoney(priorVal, currency, locale)}
          </p>
          {deltaAbs != null && (
            <p className="mt-1 text-gray-600 dark:text-gray-300">
              {t("analytics.explorer_delta", {
                abs: formatMoney(deltaAbs, currency, locale),
                pct: deltaPct == null ? "—" : `${formatNumber(deltaPct, locale, 1)}%`,
              })}
            </p>
          )}
        </>
      )}
    </div>
  );
}

function TopDescriptionsPanel({
  rows,
  currency,
  locale,
  showAll,
  onToggle,
}: {
  rows: { description: string; occurrences: number; totalAmount: string; averagePerOccurrence: string }[];
  currency: string;
  locale: string;
  showAll: boolean;
  onToggle: () => void;
}) {
  const { t } = useTranslation();
  if (rows.length === 0) {
    return (
      <div className="space-y-2">
        <h3 className="text-sm font-medium">{t("analytics.explorer_top_descriptions")}</h3>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
      </div>
    );
  }
  const visible = showAll ? rows : rows.slice(0, TOP_DESC_DEFAULT_LIMIT);
  const hasMore = rows.length > TOP_DESC_DEFAULT_LIMIT;
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-medium">{t("analytics.explorer_top_descriptions")}</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-left text-gray-500 dark:text-gray-400">
            <tr>
              <th className="py-2">{t("common.description")}</th>
              <th className="text-right">{t("analytics.explorer_occurrences")}</th>
              <th className="text-right">{t("analytics.explorer_total")}</th>
              <th className="text-right">{t("analytics.explorer_avg_per_occurrence")}</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((r, idx) => (
              <tr key={`${r.description}-${idx}`} className="border-t border-border">
                <td className="py-2">
                  {r.description.length === 0 ? (
                    <span className="text-gray-400">{t("tx.no_description")}</span>
                  ) : (
                    r.description
                  )}
                </td>
                <td className="text-right">{r.occurrences}</td>
                <td className="text-right">{formatMoney(r.totalAmount, currency, locale)}</td>
                <td className="text-right">{formatMoney(r.averagePerOccurrence, currency, locale)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hasMore && (
        <button
          type="button"
          onClick={onToggle}
          className="text-sm font-medium text-primary hover:underline"
        >
          {showAll
            ? t("analytics.explorer_show_top", { count: TOP_DESC_DEFAULT_LIMIT })
            : t("analytics.explorer_show_all", { count: rows.length })}
        </button>
      )}
    </div>
  );
}
