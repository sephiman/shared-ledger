import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { usePortfolioSummary } from "@/api/portfolio";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { pnlTone, signedMoney, percentLabel, returnPercents } from "@/features/portfolio/valuation";

function toneClass(tone: ReturnType<typeof pnlTone>): string {
  if (tone === "positive") return "text-green-600 dark:text-green-400";
  if (tone === "negative") return "text-red-600 dark:text-red-400";
  return "text-gray-600 dark:text-gray-300";
}

export function PortfolioOverviewTile({ householdId, currency, locale }: { householdId: string; currency: string; locale: string }) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { data } = usePortfolioSummary(householdId);

  // Render nothing when the household has no holdings (no empty-state placeholder).
  if (!data || data.holdings.length === 0) return null;

  // All three percentages follow the user's chosen basis, from one shared computation path.
  const rp = returnPercents(user?.portfolioReturnBasis ?? "OPEN_COST", data);
  // Under NET_INVESTED house money there is no base to divide by; explain the "—" on hover.
  const houseMoneyTitle = rp.houseMoney ? t("portfolio.net_invested_unavailable") : undefined;

  const money = (v: string | null) => (v == null ? "—" : formatMoney(v, currency, locale));
  // Signed money plus the percentage over its own denominator, e.g. "+€1,234 (11.1%)";
  // an undefined percentage (zero denominator) renders as "(—)", never 0%.
  const pnlText = (v: string | null, fraction: string | null): string =>
    v == null ? "—" : `${signedMoney(v, money(v))} (${percentLabel(fraction, locale)})`;
  // One instantiated explanation line, e.g. "Realized +€5,000.00 over €10,000.00 … = +50.0%".
  const pctTooltip = (key: string, v: string | null, fraction: string | null, basis: string): string | undefined =>
    v == null || fraction == null
      ? undefined
      : t(key, { pnl: signedMoney(v, money(v)), basis: money(basis), pct: percentLabel(fraction, locale, true) });

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
          <p
            className={`mt-2 text-sm font-medium tabular-nums ${toneClass(pnlTone(data.totalReturn))}`}
            title={houseMoneyTitle ?? pctTooltip("portfolio.total_return_pct_tooltip", data.totalReturn, rp.totalReturnPct, rp.totalBasis)}
          >
            {pnlText(data.totalReturn, rp.totalReturnPct)}
            <span className="ml-1 font-normal text-gray-500 dark:text-gray-400">{t("portfolio.total_return")}</span>
          </p>
        )}
        <div className="mt-1 grid grid-cols-2 gap-x-3 text-xs">
          <span
            className={`tabular-nums ${toneClass(pnlTone(data.totalUnrealizedPnl))}`}
            title={houseMoneyTitle ?? pctTooltip("portfolio.unrealized_pct_tooltip", data.totalUnrealizedPnl, rp.unrealizedPnlPct, rp.unrealizedBasis)}
          >
            {t("portfolio.unrealized_pnl")}: {pnlText(data.totalUnrealizedPnl, rp.unrealizedPnlPct)}
          </span>
          <span
            className={`tabular-nums ${toneClass(pnlTone(data.totalRealizedPnl))}`}
            title={houseMoneyTitle ?? pctTooltip("portfolio.realized_pct_tooltip", data.totalRealizedPnl, rp.realizedPnlPct, rp.realizedBasis)}
          >
            {t("portfolio.realized_pnl")}: {pnlText(data.totalRealizedPnl, rp.realizedPnlPct)}
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
