import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { usePortfolioSummary } from "@/api/portfolio";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, formatNumber } from "@/lib/money";
import { pnlTone, signedMoney, percentOf } from "@/features/portfolio/valuation";

function toneClass(tone: ReturnType<typeof pnlTone>): string {
  if (tone === "positive") return "text-green-600 dark:text-green-400";
  if (tone === "negative") return "text-red-600 dark:text-red-400";
  return "text-gray-600 dark:text-gray-300";
}

export function PortfolioOverviewTile({ householdId, currency, locale }: { householdId: string; currency: string; locale: string }) {
  const { t } = useTranslation();
  const { data } = usePortfolioSummary(householdId);

  // Render nothing when the household has no holdings (no empty-state placeholder).
  if (!data || data.holdings.length === 0) return null;

  const money = (v: string | null) => (v == null ? "—" : formatMoney(v, currency, locale));
  // Signed money plus its percent of the cost basis, e.g. "+€1,234 (11.1%)".
  const pnlText = (v: string | null): string => {
    if (v == null) return "—";
    const pct = percentOf(v, data.totalCostBasis);
    return `${signedMoney(v, money(v))}${pct != null ? ` (${formatNumber(pct, locale, 1)}%)` : ""}`;
  };

  const top = [...data.holdings]
    .filter((h) => h.currentValue != null)
    .sort((a, b) => Number(b.currentValue) - Number(a.currentValue))
    .slice(0, 3);

  return (
    <Card>
      <CardHeader className="flex items-center justify-between">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("portfolio.title")}</p>
        <Link to="/networth?tab=holdings" className="text-sm font-medium text-primary hover:underline">
          {t("portfolio.home_view_all")}
        </Link>
      </CardHeader>
      <CardBody>
        <p className="text-3xl font-semibold tabular-nums">{money(data.totalValue)}</p>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("portfolio.current_value")}</p>

        {data.totalReturn != null && (
          <p className={`mt-2 text-sm font-medium tabular-nums ${toneClass(pnlTone(data.totalReturn))}`}>
            {pnlText(data.totalReturn)}
            <span className="ml-1 font-normal text-gray-500 dark:text-gray-400">{t("portfolio.total_return")}</span>
          </p>
        )}
        <div className="mt-1 grid grid-cols-2 gap-x-3 text-xs">
          <span className={`tabular-nums ${toneClass(pnlTone(data.totalUnrealizedPnl))}`}>
            {t("portfolio.unrealized_pnl")}: {pnlText(data.totalUnrealizedPnl)}
          </span>
          <span className={`tabular-nums ${toneClass(pnlTone(data.totalRealizedPnl))}`}>
            {t("portfolio.realized_pnl")}: {pnlText(data.totalRealizedPnl)}
          </span>
        </div>
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400 tabular-nums">
          {t("portfolio.invested")}: {money(data.totalCostBasis)}
        </p>

        <ul className="mt-3 space-y-1 text-sm">
          {top.map((h) => (
            <li key={h.holding.id} className="flex justify-between">
              <span className="truncate">{h.holding.symbol}</span>
              <span className="font-mono tabular-nums">{money(h.currentValue)}</span>
            </li>
          ))}
        </ul>

        {data.anyStale && (
          <p className="mt-3 text-xs text-amber-600">{t("portfolio.stale_prices_warning")}</p>
        )}
      </CardBody>
    </Card>
  );
}
