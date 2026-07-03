import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  usePortfolioEvolution,
  usePortfolioSummary,
  type HoldingAssetClass,
} from "@/api/portfolio";
import { Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatMoney } from "@/lib/money";
import { isoToday } from "@/lib/dates";
import { ChartTooltip } from "@/components/charts/ChartTooltip";

const VALUE_COLOR = "#0ea5e9";
const INVESTED_COLOR = "#6b7280";
const REALIZED_COLOR = "#22c55e";
const UNREALIZED_COLOR = "#a855f7";

const ASSET_CLASSES: HoldingAssetClass[] = ["crypto", "etf", "stock", "fund"];

type RangePreset = "3m" | "6m" | "1y" | "2y" | "all" | "custom";

const RANGE_MONTHS: Record<Exclude<RangePreset, "all" | "custom">, number> = {
  "3m": 3,
  "6m": 6,
  "1y": 12,
  "2y": 24,
};

function isoMonthsAgo(months: number): string {
  const d = new Date();
  d.setMonth(d.getMonth() - months);
  return d.toISOString().slice(0, 10);
}

export function PortfolioEvolutionTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: summary } = usePortfolioSummary(household.householdId);

  const [assetClass, setAssetClass] = useState<HoldingAssetClass | "">("");
  const [holdingId, setHoldingId] = useState("");
  const [range, setRange] = useState<RangePreset>("1y");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState(isoToday());

  const holdingOptions = useMemo(
    () =>
      (summary?.holdings ?? [])
        .map((row) => row.holding)
        .filter((h) => !assetClass || h.assetClass === assetClass),
    [summary, assetClass],
  );

  const filters = useMemo(() => {
    const from =
      range === "all" ? undefined : range === "custom" ? customFrom || undefined : isoMonthsAgo(RANGE_MONTHS[range]);
    const to = range === "custom" ? customTo || undefined : undefined;
    return {
      from,
      to,
      assetClass: assetClass || undefined,
      holdingId: holdingId || undefined,
    };
  }, [range, customFrom, customTo, assetClass, holdingId]);

  const { data: evolution } = usePortfolioEvolution(household.householdId, filters);

  const data = useMemo(
    () =>
      (evolution?.points ?? []).map((p) => ({
        date: p.date,
        value: Number(p.value),
        invested: Number(p.invested),
        realized: Number(p.realizedPnl),
        unrealized: Number(p.unrealizedPnl),
      })),
    [evolution],
  );

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
          <div className="w-40">
            <Label>{t("portfolio.range")}</Label>
            <Select value={range} onChange={(e) => setRange(e.target.value as RangePreset)}>
              <option value="3m">{t("portfolio.range_months", { count: 3 })}</option>
              <option value="6m">{t("portfolio.range_months", { count: 6 })}</option>
              <option value="1y">{t("portfolio.range_year", { count: 1 })}</option>
              <option value="2y">{t("portfolio.range_year", { count: 2 })}</option>
              <option value="all">{t("portfolio.range_all")}</option>
              <option value="custom">{t("portfolio.range_custom")}</option>
            </Select>
          </div>
          {range === "custom" && (
            <>
              <div className="w-40">
                <Label>{t("tx.filter_from")}</Label>
                <Input type="date" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} />
              </div>
              <div className="w-40">
                <Label>{t("tx.filter_to")}</Label>
                <Input type="date" value={customTo} onChange={(e) => setCustomTo(e.target.value)} />
              </div>
            </>
          )}
        </div>
      </CardHeader>
      <CardBody className="h-96">
        {data.length === 0 ? (
          <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" />
              <YAxis />
              <Tooltip
                content={(props) => (
                  <ChartTooltip
                    {...props}
                    formatValue={(v) => formatMoney(Number(v), household.currency, i18n.language)}
                  />
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
        )}
      </CardBody>
    </Card>
  );
}
