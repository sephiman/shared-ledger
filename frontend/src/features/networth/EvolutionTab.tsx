import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useSnapshots } from "@/api/networth";
import { useContributionSeries } from "@/api/analytics";
import { useAssetClasses } from "@/api/catalog";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
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

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444", "#14b8a6"];

interface EvolutionRow {
  date: string;
  netWorth: number;
  contributions: number | null;
  [classKey: string]: number | string | null;
}

export function EvolutionTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: snapshots = [] } = useSnapshots(household.householdId);
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: contributions } = useContributionSeries(household.householdId);

  const contributionByDate = useMemo(() => {
    const m = new Map<string, number>();
    if (contributions) {
      for (const p of contributions.points) {
        m.set(p.snapshotDate, Number(p.netContribution));
      }
    }
    return m;
  }, [contributions]);

  const data = useMemo<EvolutionRow[]>(() => {
    return snapshots.map((s) => {
      const row: EvolutionRow = {
        date: s.snapshotDate,
        netWorth: 0,
        contributions: contributionByDate.get(s.snapshotDate) ?? null,
      };
      let totalAssets = 0;
      for (const cls of assetClasses) {
        const v = Number(s.assets.find((a) => a.assetClassCode === cls.code)?.value ?? 0);
        row[cls.code] = v;
        totalAssets += v;
      }
      const totalLiabilities = Number(s.totalLiabilities);
      row.netWorth = totalAssets - totalLiabilities;
      return row;
    });
  }, [snapshots, assetClasses, contributionByDate]);

  const overlayVisible = (contributions?.points.length ?? 0) > 0;

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("networth.evolution")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("networth.evolution_description")}</p>
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
                    currency={household.currency}
                    locale={i18n.language}
                    showContributions={overlayVisible}
                  />
                )}
              />
              <Legend />
              {assetClasses.map((cls, idx) => (
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
              ))}
              <Line
                type="monotone"
                dataKey="netWorth"
                stroke="#111827"
                strokeWidth={2}
                dot={false}
                name={t("networth.net_worth")}
              />
              {overlayVisible && (
                <Line
                  type="monotone"
                  dataKey="contributions"
                  stroke="#6b7280"
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

interface TooltipPayloadItem {
  payload?: EvolutionRow;
}

interface TooltipProps {
  active?: boolean;
  label?: string | number;
  payload?: ReadonlyArray<TooltipPayloadItem>;
  assetClasses: { code: string }[];
  currency: string;
  locale: string;
  showContributions: boolean;
}

function EvolutionTooltip({ active, label, payload, assetClasses, currency, locale, showContributions }: TooltipProps) {
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
    <div className="rounded-md border border-border bg-white p-3 text-xs shadow-sm dark:bg-gray-800">
      <p className="mb-2 font-medium">{label}</p>
      <p>
        <span className="text-gray-500 dark:text-gray-400">{t("networth.net_worth")}: </span>
        <span className="font-medium">{formatMoney(netWorth, currency, locale)}</span>
      </p>
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
      {assetClasses.map((cls) => {
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
