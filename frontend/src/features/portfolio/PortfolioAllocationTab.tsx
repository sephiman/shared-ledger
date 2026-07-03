import { useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { usePortfolioSummary } from "@/api/portfolio";
import type { HoldingAssetClass } from "@/api/portfolio";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { formatMoney } from "@/lib/money";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { AllocationTab as SnapshotAllocationPanel } from "@/features/networth/AllocationTab";

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444", "#14b8a6"];

interface Slice {
  name: string;
  value: number;
  fill: string;
}

export function PortfolioAllocationTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: summary } = usePortfolioSummary(household.householdId);

  const bySymbol = useMemo(() => {
    const build = (classes: HoldingAssetClass[]): Slice[] =>
      (summary?.holdings ?? [])
        .filter(
          (h) =>
            classes.includes(h.holding.assetClass) &&
            h.currentValue != null &&
            Number(h.currentValue) > 0,
        )
        .map((h, i) => ({
          name: h.holding.symbol,
          value: Number(h.currentValue),
          fill: PALETTE[i % PALETTE.length],
        }));
    return {
      all: build(["crypto", "etf", "stock", "fund"]),
      crypto: build(["crypto"]),
      equity: build(["etf", "stock"]),
    };
  }, [summary]);

  const byClass = useMemo<Slice[]>(() => {
    if (!summary) return [];
    return Object.entries(summary.byClass)
      .filter(([, v]) => Number(v) > 0)
      .map(([code, v], i) => ({
        name: t(`asset.${code}`, code),
        value: Number(v),
        fill: PALETTE[i % PALETTE.length],
      }));
  }, [summary, t]);

  const formatValue = (v: unknown) =>
    formatMoney(Number(v), household.currency, i18n.language);

  const panel = (title: string, data: Slice[]): ReactNode => (
    <Card>
      <CardHeader>
        <p className="font-medium">{title}</p>
      </CardHeader>
      <CardBody className="h-96">
        {data.length === 0 ? (
          <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={data} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={110} label />
              <Tooltip content={(props) => <ChartTooltip {...props} formatValue={formatValue} />} />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
        )}
      </CardBody>
    </Card>
  );

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {panel(t("portfolio.allocation_by_holding"), bySymbol.all)}
      {panel(t("portfolio.allocation_by_class"), byClass)}
      {bySymbol.crypto.length > 0 && panel(t("portfolio.allocation_by_crypto"), bySymbol.crypto)}
      {bySymbol.equity.length > 0 && panel(t("portfolio.allocation_by_equity"), bySymbol.equity)}
      {/* Fifth panel: share of each asset class in the latest snapshot (incl. cash/pension). */}
      <SnapshotAllocationPanel title={t("portfolio.allocation_by_snapshots")} />
    </div>
  );
}
