import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useSnapshots } from "@/api/networth";
import { useContributionSeries } from "@/api/analytics";
import { useAssetClasses } from "@/api/catalog";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { RangeSelector } from "@/components/ui/RangeSelector";
import { defaultRange, type RangeValue } from "@/lib/range";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatMoney, formatNumber } from "@/lib/money";
import { cn } from "@/lib/cn";
import {
  buildEvolutionRows,
  filterSnapshotsByRange,
  CONTRIBUTIONS_KEY,
  LIABILITIES_KEY,
  NET_WORTH_KEY,
  type EvolutionRow,
} from "./evolution";

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444", "#14b8a6"];
const NET_WORTH_COLOR = "#111827";
const CONTRIBUTIONS_COLOR = "#6b7280";
const LIABILITIES_COLOR = "#dc2626";

interface LegendEntry {
  key: string;
  name: string;
  color: string;
}

export function EvolutionTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: snapshots = [] } = useSnapshots(household.householdId);
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: contributions } = useContributionSeries(household.householdId);

  const [range, setRange] = useState<RangeValue>(defaultRange("all"));
  // Legend keys that are toggled off. Net worth is recomputed from whatever stays visible.
  const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set());
  const toggle = (key: string) =>
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const contributionByDate = useMemo(() => {
    const m = new Map<string, number>();
    if (contributions) {
      for (const p of contributions.points) {
        m.set(p.snapshotDate, Number(p.netContribution));
      }
    }
    return m;
  }, [contributions]);

  const filtered = useMemo(() => filterSnapshotsByRange(snapshots, range), [snapshots, range]);
  const data = useMemo(
    () => buildEvolutionRows(filtered, assetClasses, contributionByDate, hidden),
    [filtered, assetClasses, contributionByDate, hidden],
  );

  const overlayVisible = (contributions?.points.length ?? 0) > 0;
  const liabilitiesPresent = useMemo(() => data.some((r) => r.liabilities > 0), [data]);
  const showContributions = overlayVisible && !hidden.has(CONTRIBUTIONS_KEY);

  // Full legend (including hidden entries, which render dimmed) — clicking any toggles it.
  const legendEntries = useMemo<LegendEntry[]>(() => {
    const entries: LegendEntry[] = assetClasses.map((cls, idx) => ({
      key: cls.code,
      name: t(`asset.${cls.code}`),
      color: PALETTE[idx % PALETTE.length],
    }));
    entries.push({ key: NET_WORTH_KEY, name: t("networth.net_worth"), color: NET_WORTH_COLOR });
    if (liabilitiesPresent) entries.push({ key: LIABILITIES_KEY, name: t("networth.liabilities"), color: LIABILITIES_COLOR });
    if (overlayVisible) entries.push({ key: CONTRIBUTIONS_KEY, name: t("networth.contributions"), color: CONTRIBUTIONS_COLOR });
    return entries;
  }, [assetClasses, liabilitiesPresent, overlayVisible, t]);

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("networth.evolution")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("networth.evolution_description")}</p>
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <RangeSelector value={range} onChange={setRange} />
        </div>
      </CardHeader>
      <CardBody className="h-96">
        {data.length === 0 ? (
          <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" />
              <YAxis />
              <Tooltip
                content={(props) => (
                  <EvolutionTooltip
                    {...props}
                    assetClasses={assetClasses}
                    hidden={hidden}
                    currency={household.currency}
                    locale={i18n.language}
                    showContributions={showContributions}
                    showLiabilities={liabilitiesPresent && !hidden.has(LIABILITIES_KEY)}
                  />
                )}
              />
              <Legend
                content={<ClickableLegend entries={legendEntries} hidden={hidden} onToggle={toggle} />}
              />
              {assetClasses.map((cls, idx) =>
                hidden.has(cls.code) ? null : (
                  <Area
                    key={cls.code}
                    type="monotone"
                    dataKey={cls.code}
                    stackId="assets"
                    stroke={PALETTE[idx % PALETTE.length]}
                    fill={PALETTE[idx % PALETTE.length]}
                    name={t(`asset.${cls.code}`)}
                    fillOpacity={0.7}
                  />
                ),
              )}
              {!hidden.has(NET_WORTH_KEY) && (
                <Line
                  type="monotone"
                  dataKey="netWorth"
                  stroke={NET_WORTH_COLOR}
                  strokeWidth={2}
                  dot={false}
                  name={t("networth.net_worth")}
                />
              )}
              {liabilitiesPresent && !hidden.has(LIABILITIES_KEY) && (
                <Line
                  type="monotone"
                  dataKey="liabilities"
                  stroke={LIABILITIES_COLOR}
                  strokeWidth={1.5}
                  strokeDasharray="4 4"
                  dot={false}
                  name={t("networth.liabilities")}
                />
              )}
              {showContributions && (
                <Line
                  type="monotone"
                  dataKey="contributions"
                  stroke={CONTRIBUTIONS_COLOR}
                  strokeWidth={1.5}
                  strokeDasharray="4 4"
                  dot={false}
                  name={t("networth.contributions")}
                  connectNulls
                />
              )}
            </AreaChart>
          </ResponsiveContainer>
        )}
      </CardBody>
      {data.length > 0 && contributions && !overlayVisible && (
        <div className="border-t border-border px-4 py-3">
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.no_movements_hint")}</p>
        </div>
      )}
    </Card>
  );
}

/** Clickable chart legend rendering every series, including hidden ones (dimmed + struck through), so a
 *  toggled-off series can always be brought back. Recharts injects extra props we ignore. */
function ClickableLegend({
  entries,
  hidden,
  onToggle,
}: {
  entries: LegendEntry[];
  hidden: ReadonlySet<string>;
  onToggle: (key: string) => void;
}) {
  return (
    <ul className="flex flex-wrap justify-center gap-x-4 gap-y-1 pt-2 text-xs">
      {entries.map((e) => {
        const isHidden = hidden.has(e.key);
        return (
          <li key={e.key}>
            <button
              type="button"
              onClick={() => onToggle(e.key)}
              aria-pressed={!isHidden}
              className={cn(
                "inline-flex items-center gap-1.5 transition-opacity focus:outline-none focus-visible:ring-2 focus-visible:ring-primary",
                isHidden ? "opacity-40" : "opacity-100",
              )}
            >
              <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: e.color }} />
              <span className={cn("text-gray-700 dark:text-gray-200", isHidden && "line-through")}>{e.name}</span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

interface TooltipPayloadItem {
  payload?: EvolutionRow;
}

interface TooltipProps {
  active?: boolean;
  label?: string | number;
  payload?: ReadonlyArray<TooltipPayloadItem>;
  assetClasses: { code: string }[];
  hidden: ReadonlySet<string>;
  currency: string;
  locale: string;
  showContributions: boolean;
  showLiabilities: boolean;
}

function EvolutionTooltip({
  active,
  label,
  payload,
  assetClasses,
  hidden,
  currency,
  locale,
  showContributions,
  showLiabilities,
}: TooltipProps) {
  const { t } = useTranslation();
  if (!active || !payload || payload.length === 0) return null;
  const row = payload[0]?.payload as EvolutionRow | undefined;
  if (!row) return null;
  const netWorth = row.netWorth;
  const contributions = row.contributions;
  const revaluation = contributions != null ? netWorth - contributions : null;
  const revaluationPct = contributions != null && contributions !== 0
    ? (revaluation! / contributions) * 100
    : null;

  return (
    <div className="rounded-md border border-border bg-overlay p-3 text-xs shadow-sm">
      <p className="mb-2 font-medium">{label}</p>
      {!hidden.has(NET_WORTH_KEY) && (
        <p>
          <span className="text-gray-500 dark:text-gray-400">{t("networth.net_worth")}: </span>
          <span className="font-medium">{formatMoney(netWorth, currency, locale)}</span>
        </p>
      )}
      {showContributions && contributions != null && (
        <>
          <p>
            <span className="text-gray-500 dark:text-gray-400">{t("networth.contributions_to_date")}: </span>
            <span className="font-medium">{formatMoney(contributions, currency, locale)}</span>
          </p>
          <p>
            <span className="text-gray-500 dark:text-gray-400">{t("networth.revaluation")}: </span>
            <span className="font-medium">{formatMoney(revaluation ?? 0, currency, locale)}</span>
            {revaluationPct != null && (
              <span className="ml-1 text-gray-500 dark:text-gray-400">({formatNumber(revaluationPct, locale, 1)}%)</span>
            )}
          </p>
        </>
      )}
      {showLiabilities && row.liabilities > 0 && (
        <p>
          <span className="text-gray-500 dark:text-gray-400">{t("networth.liabilities")}: </span>
          <span>{formatMoney(row.liabilities, currency, locale)}</span>
        </p>
      )}
      {assetClasses.map((cls) => {
        if (hidden.has(cls.code)) return null;
        const v = row[cls.code];
        if (typeof v !== "number" || v === 0) return null;
        return (
          <p key={cls.code}>
            <span className="text-gray-500 dark:text-gray-400">{t(`asset.${cls.code}`)}: </span>
            <span>{formatMoney(v, currency, locale)}</span>
          </p>
        );
      })}
    </div>
  );
}
