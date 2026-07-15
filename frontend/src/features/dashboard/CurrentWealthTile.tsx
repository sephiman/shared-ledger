import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { useAssetClasses } from "@/api/catalog";
import { useAssets, useLiabilities, useNamedValuesAt, useSnapshotPrefill } from "@/api/networth";
import { usePortfolioValuation } from "@/api/portfolio";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { isoToday } from "@/lib/dates";
import { computeCurrentWealth } from "@/features/networth/currentWealth";

export function CurrentWealthTile({ householdId, currency, locale }: { householdId: string; currency: string; locale: string }) {
  const { t } = useTranslation();
  const today = isoToday();
  const { data: assetClasses } = useAssetClasses();
  const { data: prefill } = useSnapshotPrefill(householdId);
  const { data: namedAssets } = useAssets(householdId);
  const { data: liabilities } = useLiabilities(householdId);
  const { data: valuation } = usePortfolioValuation(householdId, today);
  const { data: namedValues } = useNamedValuesAt(householdId, today, true);

  // Wait for every source so the tile never flashes stale carried-over values.
  if (!assetClasses || !prefill || !namedAssets || !liabilities || !valuation || !namedValues) return null;

  const wealth = computeCurrentWealth({
    assetClasses,
    prefill,
    namedAssets,
    liabilities,
    portfolioByClass: valuation.byClass,
    namedValues,
  });

  // Render nothing when the household has no wealth data yet (no empty-state placeholder).
  if (wealth.isEmpty) return null;

  const money = (v: Decimal) => formatMoney(v, currency, locale);

  const lines: { key: string; label: string; value: Decimal }[] = [
    { key: "cash", label: t("asset.cash"), value: wealth.cash },
    { key: "portfolio", label: t("portfolio.title"), value: wealth.portfolio },
    ...(wealth.pension.gt(0) ? [{ key: "pension", label: t("asset.pension"), value: wealth.pension }] : []),
    { key: "assets", label: t("networth.assets"), value: wealth.namedAssets },
    { key: "liabilities", label: t("networth.liabilities"), value: wealth.liabilities.isZero() ? wealth.liabilities : wealth.liabilities.neg() },
  ];

  return (
    <Card>
      <CardHeader className="flex items-center justify-between">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("wealth.home_title")}</p>
        <Link to="/networth?tab=evolution" className="text-sm font-medium text-primary hover:underline">
          {t("wealth.home_view_all")}
        </Link>
      </CardHeader>
      <CardBody>
        <p className="text-3xl font-semibold tabular-nums">{money(wealth.netWorth)}</p>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("networth.net_worth")}</p>
        <ul className="mt-3 space-y-1 text-sm">
          {lines.map((line) => (
            <li key={line.key} className="flex justify-between gap-3">
              <span className="truncate text-gray-600 dark:text-gray-300">{line.label}</span>
              <span className="font-mono tabular-nums">{money(line.value)}</span>
            </li>
          ))}
        </ul>
      </CardBody>
    </Card>
  );
}
