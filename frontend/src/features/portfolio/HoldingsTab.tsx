import { Fragment, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { useActiveHousehold, useAuth } from "@/auth/AuthContext";
import {
  useAddLot,
  useCreateHolding,
  useDeleteHolding,
  useDeleteLot,
  useLinkHolding,
  usePortfolioSummary,
  useUnlinkHolding,
  useUpdateHolding,
  useUpdateLot,
  type Holding,
  type HoldingAssetClass,
  type HoldingSummary,
  type Lot,
  type LotType,
  type PriceAlert,
  type SymbolCandidate,
} from "@/api/portfolio";
import { apiErrorMessage } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Toggle } from "@/components/ui/primitives";
import { InfoTip } from "@/components/ui/InfoTip";
import { formatFullPrecisionMoney, formatMoney, formatNumber, formatPrice, formatQuantity, formatQuantityFull } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";
import { useToggleSet } from "@/lib/useToggleSet";
import { SymbolSearchCombobox } from "./SymbolSearchCombobox";
import { usePriceRefresh } from "./usePriceRefresh";
import { RefreshPricesButton } from "./RefreshPricesButton";
import { PriceAlertModal } from "./PriceAlertModal";
import { PriceAlertsDrawer } from "./PriceAlertsDrawer";
import { isAlertEligibleHolding } from "./priceAlerts";
import { DUST_HOLDING_MAX_VALUE_EUR, isDustHolding, partitionDust } from "./dustHoldings";
import { weightsWithin } from "./holdingWeights";
import { nextSort, sortHoldings, type HoldingSort, type HoldingSortKey } from "./holdingsSort";
import { fractionToPercent, percentLabel, pnlTone, returnPercents, signedMoney } from "./valuation";
import { formatPriceAge, oldestStalePriceAge, priceAge, priceAsOfLabel, type PriceAge } from "./priceFreshness";
import { cn } from "@/lib/cn";

const ASSET_CLASSES: HoldingAssetClass[] = ["crypto", "etf", "stock", "fund"];

interface HoldingColumn {
  key: HoldingSortKey;
  labelKey: string;
  align: "left" | "right";
  className?: string;
}

/** The desktop table's columns, in order; every one of them sorts on the figure it prints. */
const COLUMNS: HoldingColumn[] = [
  { key: "symbol", labelKey: "portfolio.symbol", align: "left", className: "w-full pr-2" },
  { key: "quantity", labelKey: "portfolio.quantity", align: "right", className: "px-1 xl:px-1.5" },
  { key: "costBasis", labelKey: "portfolio.cost_basis", align: "right", className: "px-1 xl:px-1.5" },
  { key: "currentPrice", labelKey: "portfolio.current_price", align: "right", className: "px-1 xl:px-1.5" },
  { key: "currentValue", labelKey: "portfolio.current_value", align: "right", className: "px-1 xl:px-1.5" },
  { key: "unrealizedPnl", labelKey: "portfolio.unrealized_pnl", align: "right", className: "px-1 xl:px-1.5" },
  { key: "realizedPnl", labelKey: "portfolio.realized_pnl", align: "right", className: "px-1 xl:px-1.5" },
  { key: "weight", labelKey: "portfolio.weight", align: "right", className: "px-1 xl:px-1.5" },
];

/** Per browser, like the theme: a view preference for this table, with no home in Settings. */
const SHOW_CLOSED_KEY = "sl.portfolio.showClosed";

function readShowClosed(): boolean {
  try {
    return localStorage.getItem(SHOW_CLOSED_KEY) === "1";
  } catch {
    return false;
  }
}

type PanelMode = { kind: "closed" } | { kind: "create" } | { kind: "edit"; holding: Holding };

/** Matches holding_lots.unit_price NUMERIC(28, 12) scale so derived unit prices round-trip cleanly. */
const LOT_PRICE_SCALE = 12;

/** A stored decimal at full precision: trims only pure trailing zeros (10000.000000 -> 10000) and avoids
 *  exponent notation. Used to seed the trade form's HTML number fields without a stored zero tail. */
function trimZeroTail(value: string): string {
  try {
    return new Decimal(value).toFixed();
  } catch {
    return value;
  }
}

function parseNonNegativeDecimal(raw: string): Decimal | null {
  if (!raw.trim()) return null;
  try {
    const parsed = new Decimal(raw);
    return parsed.isFinite() && parsed.gte(0) ? parsed : null;
  } catch {
    return null;
  }
}

function computeTotalPrice(quantity: string, unitPrice: string): string {
  const qty = parseNonNegativeDecimal(quantity);
  const unit = parseNonNegativeDecimal(unitPrice);
  if (qty == null || qty.lte(0) || unit == null) return "";
  return qty.mul(unit).toDecimalPlaces(LOT_PRICE_SCALE).toFixed();
}

function computeUnitPrice(quantity: string, totalPrice: string): string {
  const qty = parseNonNegativeDecimal(quantity);
  const total = parseNonNegativeDecimal(totalPrice);
  if (qty == null || qty.lte(0) || total == null) return "";
  return total.div(qty).toDecimalPlaces(LOT_PRICE_SCALE).toFixed();
}

/** Average purchase cost per unit = remaining cost basis / net quantity, in base currency; null for a
 *  closed position. Bounded to 12 dp (the scale prices are stored at) so the division tail doesn't run to
 *  decimal.js's 20 significant digits. */
function avgCost(row: HoldingSummary): string | null {
  try {
    const qty = new Decimal(row.holding.netQuantity);
    if (qty.isZero()) return null;
    return new Decimal(row.holding.remainingCostBasis).div(qty).toDecimalPlaces(12).toString();
  } catch {
    return null;
  }
}

function toneClass(tone: ReturnType<typeof pnlTone>): string {
  if (tone === "positive") return "text-green-600 dark:text-green-400";
  if (tone === "negative") return "text-red-600 dark:text-red-400";
  return "text-gray-600 dark:text-gray-300";
}

type AssetFilter = HoldingAssetClass | "all";

interface Totals {
  totalCostBasis: string;
  totalSoldCostBasis: string;
  totalValue: string;
  totalUnrealizedPnl: string | null;
  totalRealizedPnl: string;
  totalReturn: string | null;
}

/** Recomputes the totals for a subset of holdings, mirroring the backend summary math. */
function subtotals(rows: HoldingSummary[]): Totals {
  let cost = new Decimal(0);
  let sold = new Decimal(0);
  let value = new Decimal(0);
  let unrealized = new Decimal(0);
  let realized = new Decimal(0);
  let anyUnrealized = false;
  for (const r of rows) {
    cost = cost.plus(r.holding.remainingCostBasis || 0);
    sold = sold.plus(r.soldCostBasis || 0);
    if (r.currentValue != null) value = value.plus(r.currentValue);
    if (r.unrealizedPnl != null) {
      unrealized = unrealized.plus(r.unrealizedPnl);
      anyUnrealized = true;
    }
    realized = realized.plus(r.realizedPnl || 0);
  }
  return {
    totalCostBasis: cost.toString(),
    totalSoldCostBasis: sold.toString(),
    totalValue: value.toString(),
    totalUnrealizedPnl: anyUnrealized ? unrealized.toString() : null,
    totalRealizedPnl: realized.toString(),
    totalReturn: anyUnrealized ? unrealized.plus(realized).toString() : null,
  };
}

export function HoldingsTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { user } = useAuth();

  const { data: summary } = usePortfolioSummary(household.householdId);
  const create = useCreateHolding(household.householdId);
  const update = useUpdateHolding(household.householdId);
  const del = useDeleteHolding(household.householdId);
  const unlink = useUnlinkHolding(household.householdId);
  const refresh = usePriceRefresh(household.householdId);

  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  const panelRef = useRef<HTMLDivElement | null>(null);
  const expanded = useToggleSet<string>();
  const [assetFilter, setAssetFilter] = useState<AssetFilter>("all");
  const [showClosed, setShowClosed] = useState(readShowClosed);
  const [sort, setSort] = useState<HoldingSort | null>(null);
  const [alertModal, setAlertModal] = useState<{ row: HoldingSummary; existing?: PriceAlert | null } | null>(null);
  const [alertsDrawerOpen, setAlertsDrawerOpen] = useState(false);

  const telegramActive = summary?.telegramActive === true;
  const activeAlertCount = summary?.activeAlertCount ?? 0;
  const displayTimezone = summary?.displayTimezone;

  useEffect(() => {
    try {
      localStorage.setItem(SHOW_CLOSED_KEY, showClosed ? "1" : "0");
    } catch {
      // A browser that refuses storage still gets the toggle; it just won't remember it.
    }
  }, [showClosed]);

  useEffect(() => {
    if (panel.kind === "edit") panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [panel]);

  const holdings = summary?.holdings ?? [];
  const availableClasses = ASSET_CLASSES.filter((c) => holdings.some((h) => h.holding.assetClass === c));
  const effectiveFilter: AssetFilter =
    assetFilter !== "all" && !availableClasses.includes(assetFilter) ? "all" : assetFilter;
  const filtered =
    effectiveFilter === "all" ? holdings : holdings.filter((h) => h.holding.assetClass === effectiveFilter);
  // Whatever the filter leaves is what the weight column is a share of, so the percentages on screen always
  // add up to 100 % — a class filtered out is not 40 % of the table it is no longer in.
  const weights = weightsWithin(filtered);
  // Display-only, and deliberately the last step: `filtered` stays the basis for every card, the
  // stale notice and the weights, so hiding a row never moves a number.
  const { visible, hiddenCount } = partitionDust(filtered);
  const listed = showClosed ? filtered : visible;
  // Anchored to the whole portfolio rather than the current filter, so the control doesn't blink in
  // and out while switching asset types.
  const anyDust = holdings.some(isDustHolding);
  const totals: Totals | null = !summary
    ? null
    : effectiveFilter === "all"
      ? {
          totalCostBasis: summary.totalCostBasis,
          totalSoldCostBasis: summary.totalSoldCostBasis,
          totalValue: summary.totalValue,
          totalUnrealizedPnl: summary.totalUnrealizedPnl,
          totalRealizedPnl: summary.totalRealizedPnl,
          totalReturn: summary.totalReturn,
        }
      : subtotals(filtered);
  const locale = i18n.language;
  const rows = sortHoldings(listed, sort, weights, locale);
  // One reference instant for the whole render, so every row's age and the "oldest price"
  // line are measured against the same clock.
  const now = new Date();
  // Null unless something is actually stale — the summary line is a symptom, not furniture.
  const oldestStaleAge = oldestStalePriceAge(filtered, now);
  const oldestStaleLine = oldestStaleAge
    ? t("portfolio.oldest_price", { age: formatPriceAge(oldestStaleAge, locale) })
    : null;
  const money = (v: string | number | null | undefined, currency = household.currency) =>
    v == null ? "—" : formatMoney(v, currency, locale);
  // Per-unit prices (current price, avg cost); optional referencePrice caps unit-cost precision at the price above it.
  const price = (
    v: string | number | null | undefined,
    currency = household.currency,
    referencePrice?: string | null,
  ) => (v == null ? "—" : formatPrice(v, currency, locale, referencePrice));
  // Signed money plus the percentage over its own denominator, e.g. "+€1,234 (11.1%)";
  // an undefined percentage (zero denominator) renders as "(—)", never 0%.
  const pnlStat = (v: string | null | undefined, fraction: string | null): string =>
    v == null ? "—" : `${signedMoney(v, money(v))} (${percentLabel(fraction, locale)})`;
  // One instantiated explanation line, e.g. "Realized +€5,000.00 over €10,000.00 … = +50.0%".
  const pctTooltip = (key: string, v: string | null | undefined, fraction: string | null, basis: string): string | undefined =>
    v == null || fraction == null
      ? undefined
      : t(key, { pnl: signedMoney(v, money(v)), basis: money(basis), pct: percentLabel(fraction, locale, true) });
  // All three percentages follow the user's chosen basis, from one shared computation path.
  const rp = totals ? returnPercents(user?.portfolioReturnBasis ?? "OPEN_COST", totals) : null;
  // Under NET_INVESTED house money there is no base to divide by; explain the "—" on hover.
  const houseMoneyTitle = rp?.houseMoney ? t("portfolio.net_invested_unavailable") : undefined;
  // Money-weighted return cannot be derived from the filtered subtotals (it is a
  // root-solve over dated flows, not a ratio), so the class figure comes precomputed
  // from the backend and the filter just picks the matching entry.
  const mwr =
    effectiveFilter === "all" ? summary?.moneyWeightedReturn : summary?.moneyWeightedReturnByClass[effectiveFilter];
  const mwrExplain = !mwr
    ? null
    : mwr.value == null
      ? t(
          mwr.unavailableReason === "no_flows"
            ? "portfolio.xirr_unavailable_no_flows"
            : mwr.unavailableReason === "unpriced_holdings"
              ? "portfolio.xirr_unavailable_unpriced"
              : "portfolio.xirr_unavailable_not_computable",
        )
      : t(mwr.annualized ? "portfolio.xirr_explain" : "portfolio.xirr_explain_cumulative", {
          flows: mwr.flowCount,
          from: mwr.from ? formatDate(mwr.from, locale) : "—",
          to: formatDate(mwr.to, locale),
          terminal: money(mwr.terminalValue),
        });
  // The count always states what the table leaves out, so hidden never reads as missing.
  const hidingSome = hiddenCount > 0 && !showClosed;
  const countLabel =
    t("portfolio.holdings_count", { count: rows.length }) +
    (hidingSome ? ` ${t("portfolio.holdings_hidden", { count: hiddenCount })}` : "");

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        {/* `relative` + anchor="container": the icon trails a sentence that wraps differently per
            language and width, so the panel opens from this paragraph's edge, not the icon's. */}
        <p className="relative text-sm text-gray-500 dark:text-gray-400">
          {t("portfolio.description")}{" "}
          <InfoTip label={t("portfolio.cadence_info")} anchor="container">
            {t("portfolio.cadence_tooltip")}
          </InfoTip>
        </p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/portfolio/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border-strong bg-raised px-4 py-2 text-sm font-medium text-gray-900 hover:bg-raised-hover dark:text-gray-100"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={() => setPanel(panel.kind === "create" ? { kind: "closed" } : { kind: "create" })}>
            {panel.kind === "create" ? t("common.cancel") : t("portfolio.new_holding")}
          </Button>
        </div>
      </div>

      {availableClasses.length > 1 && (
        <div className="flex items-center gap-2">
          <Label className="mb-0 whitespace-nowrap">{t("portfolio.filter_by_class")}</Label>
          <Select
            className="w-auto"
            value={effectiveFilter}
            onChange={(e) => setAssetFilter(e.target.value as AssetFilter)}
          >
            <option value="all">{t("portfolio.filter_all")}</option>
            {availableClasses.map((c) => (
              <option key={c} value={c}>{t(`portfolio.class.${c}`)}</option>
            ))}
          </Select>
        </div>
      )}

      {totals && filtered.length > 0 && (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
          <Stat label={t("portfolio.invested")} value={money(totals.totalCostBasis)}>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400 tabular-nums">
              {t("portfolio.net_invested_label")}: {money(rp!.netInvested)}{" "}
              <InfoTip label={t("portfolio.net_invested_info")}>
                {t(rp!.netInvestedHouseMoney ? "portfolio.net_invested_tooltip_house" : "portfolio.net_invested_tooltip", {
                  open: money(totals.totalCostBasis),
                  realized: money(totals.totalRealizedPnl),
                })}
              </InfoTip>
            </p>
          </Stat>
          <Stat label={t("portfolio.current_value")} value={money(totals.totalValue)} />
          <Stat
            label={t("portfolio.unrealized_pnl")}
            value={pnlStat(totals.totalUnrealizedPnl, rp!.unrealizedPnlPct)}
            tone={toneClass(pnlTone(totals.totalUnrealizedPnl))}
            title={houseMoneyTitle ?? pctTooltip("portfolio.unrealized_pct_tooltip", totals.totalUnrealizedPnl, rp!.unrealizedPnlPct, rp!.unrealizedBasis)}
          />
          <Stat
            label={t("portfolio.realized_pnl")}
            value={pnlStat(totals.totalRealizedPnl, rp!.realizedPnlPct)}
            tone={toneClass(pnlTone(totals.totalRealizedPnl))}
            title={houseMoneyTitle ?? pctTooltip("portfolio.realized_pct_tooltip", totals.totalRealizedPnl, rp!.realizedPnlPct, rp!.realizedBasis)}
          />
          <Stat
            label={t("portfolio.total_return")}
            value={pnlStat(totals.totalReturn, rp!.totalReturnPct)}
            tone={toneClass(pnlTone(totals.totalReturn))}
            title={houseMoneyTitle ?? pctTooltip("portfolio.total_return_pct_tooltip", totals.totalReturn, rp!.totalReturnPct, rp!.totalBasis)}
          />
          {mwr && (
            <Stat
              label={t(mwr.annualized ? "portfolio.xirr_label" : "portfolio.xirr_label_cumulative")}
              value={percentLabel(mwr.value, locale, true)}
              tone={toneClass(pnlTone(mwr.value))}
            >
              {mwrExplain && (
                <details className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                  <summary className="cursor-pointer select-none text-sky-600 hover:underline dark:text-sky-400">
                    {t("portfolio.how_calculated")}
                  </summary>
                  <p className="mt-1">{mwrExplain}</p>
                </details>
              )}
            </Stat>
          )}
        </div>
      )}

      {summary?.anyStale && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200">
          <div className="min-w-0">
            <p>{t("portfolio.stale_prices_warning")}</p>
            {oldestStaleAge && <p className="mt-0.5 text-xs">{oldestStaleLine}</p>}
          </div>
          <Button
            variant="secondary"
            className="shrink-0"
            disabled={refresh.refreshing || refresh.nextAllowedAt != null}
            onClick={refresh.start}
          >
            {refresh.refreshing ? t("portfolio.refreshing_prices") : t("portfolio.refresh_prices")}
          </Button>
        </div>
      )}

      {/* A price can pass its own class tolerance while the backend's wider global rule stays
          silent (a 6-day-old daily close). Then there is no notice to sit inside, so the worst
          case still gets a line of its own — never shown when nothing is stale. */}
      {oldestStaleAge && !summary?.anyStale && (
        <p className="text-xs text-amber-700 dark:text-amber-300">{oldestStaleLine}</p>
      )}

      {panel.kind !== "closed" && (
        <div ref={panelRef}>
          <HoldingFormPanel
            key={panel.kind === "edit" ? panel.holding.id : "create"}
            editing={panel.kind === "edit" ? panel.holding : null}
            onClose={() => setPanel({ kind: "closed" })}
            onCreate={async (input) => create.mutateAsync(input)}
            onUpdate={async (id, input) => update.mutateAsync({ id, input })}
          />
        </div>
      )}

      <Card>
        {holdings.length > 0 && (
          <CardHeader className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <p
                className="text-sm text-gray-500 dark:text-gray-400"
                title={hidingSome ? t("portfolio.holdings_hidden_tooltip", { threshold: money(DUST_HOLDING_MAX_VALUE_EUR) }) : undefined}
              >
                {countLabel}
              </p>
              {telegramActive && (
                <button
                  type="button"
                  onClick={() => setAlertsDrawerOpen(true)}
                  className="inline-flex items-center gap-1 rounded-full border border-border bg-surface px-2.5 py-0.5 text-xs font-medium text-sky-700 hover:bg-gray-100 dark:text-sky-300 dark:hover:bg-raised"
                >
                  <span aria-hidden>🔔</span>
                  <span>
                    {activeAlertCount > 0
                      ? t("portfolio.alerts.count", { count: activeAlertCount })
                      : t("portfolio.alerts.count_zero")}
                  </span>
                </button>
              )}
            </div>
            <div className="flex flex-wrap items-center justify-end gap-x-4 gap-y-1">
              <RefreshPricesButton householdId={household.householdId} />
              {anyDust && (
                <Toggle label={t("portfolio.show_closed")} checked={showClosed} onChange={setShowClosed} />
              )}
            </div>
          </CardHeader>
        )}
        <CardBody>
          {holdings.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("portfolio.empty")}</p>
          ) : rows.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("portfolio.all_closed_hidden", { count: hiddenCount })}</p>
          ) : (
            <>
              <ul className="space-y-2 lg:hidden">
                {rows.map((row) => {
                  const isOpen = expanded.has(row.holding.id);
                  const age = priceAge(row, now);
                  return (
                    <li key={row.holding.id} className="rounded-md border border-border">
                      <div className="flex items-start gap-2 p-3">
                      <button
                        type="button"
                        onClick={() => expanded.toggle(row.holding.id)}
                        aria-expanded={isOpen}
                        className="min-w-0 flex-1 text-left"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0 flex-1">
                            <p className="font-medium">
                              <span className="mr-1 inline-block w-3 text-xs text-gray-400" aria-hidden>{isOpen ? "▾" : "▸"}</span>
                              {row.holding.symbol}
                              <span className="ml-2 text-xs font-normal text-gray-500 dark:text-gray-400">
                                {t(`portfolio.class.${row.holding.assetClass}`)}
                              </span>
                              {row.holding.closed && (
                                <span className="ml-2 rounded-full bg-gray-200 px-2 py-0.5 text-xs font-normal text-gray-600 dark:bg-raised dark:text-gray-300">
                                  {t("portfolio.closed")}
                                </span>
                              )}
                              <ProviderNotListedBadge holding={row.holding} locale={locale} />
                            </p>
                            {row.holding.label && (
                              <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">{row.holding.label}</p>
                            )}
                            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                              <span title={formatQuantityFull(row.holding.netQuantity, locale)}>
                                {formatQuantity(row.holding.netQuantity, locale)}
                              </span>{" "}
                              · {t("portfolio.cost_basis")}: {money(row.holding.remainingCostBasis)}
                              {/* No price column to mark on a card, so the dot trails this line — and
                                  costs no extra height. Expanding the card gives the date in words. */}
                              {age?.stale && <StalePriceDot age={age} locale={locale} className="ml-1.5" />}
                            </p>
                            <PriceBadge row={row} />
                          </div>
                          <div className="flex flex-col items-end gap-1">
                            <span className="font-medium">{money(row.currentValue)}</span>
                            {row.unrealizedPnl != null && (
                              <span className={`text-xs ${toneClass(pnlTone(row.unrealizedPnl))}`}>
                                {signedMoney(row.unrealizedPnl, money(row.unrealizedPnl))}
                                {row.unrealizedPnlPct != null && ` (${formatNumber(fractionToPercent(row.unrealizedPnlPct) ?? 0, locale, 1)}%)`}
                              </span>
                            )}
                            {pnlTone(row.realizedPnl) !== "neutral" && (
                              <span className={`text-xs ${toneClass(pnlTone(row.realizedPnl))}`}>
                                {t("portfolio.realized_short")}: {signedMoney(row.realizedPnl, money(row.realizedPnl))}
                              </span>
                            )}
                          </div>
                        </div>
                      </button>
                      {telegramActive && isAlertEligibleHolding(row) && (
                        <AlertBellButton row={row} onClick={() => setAlertModal({ row })} />
                      )}
                      </div>
                      {isOpen && (
                        <div className="border-t border-border px-3 py-2">
                          <HoldingDetail
                            row={row}
                            telegramActive={telegramActive}
                            onOpenAlert={() => setAlertModal({ row })}
                            onEdit={() => setPanel({ kind: "edit", holding: row.holding })}
                            onDelete={() => {
                              if (window.confirm(t("common.delete") + "?")) void del.mutate(row.holding.id);
                            }}
                            onUnlink={() => void unlink.mutate(row.holding.id)}
                          />
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
              <div className="hidden overflow-x-auto lg:block">
              <table className="w-full text-xs xl:text-sm">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="w-5 px-0.5 py-2"></th>
                    {COLUMNS.map((column) => (
                      <SortableColumn
                        key={column.key}
                        column={column}
                        sort={sort}
                        onSort={(key) => setSort((current) => nextSort(current, key))}
                      />
                    ))}
                    {telegramActive && (
                      <th className="w-10 px-1 py-2 text-right font-normal">
                        <span className="sr-only">{t("portfolio.alerts.drawer_title")}</span>
                      </th>
                    )}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => {
                    const isOpen = expanded.has(row.holding.id);
                    const avg = avgCost(row);
                    const age = priceAge(row, now);
                    const priceCcy = row.priceCurrency ?? row.holding.nativeCurrency;
                    const priceText = row.currentPrice != null ? price(row.currentPrice, priceCcy) : null;
                    const fullPriceText = row.currentPrice != null ? formatFullPrecisionMoney(row.currentPrice, priceCcy, locale) : null;
                    const priceTitleParts = [
                      row.priceAsOf
                        ? t("portfolio.price_as_of", { date: formatDate(row.priceAsOf, locale) }) +
                          (row.stale ? ` · ${t("portfolio.stale_price")}` : "")
                        : null,
                      fullPriceText && fullPriceText !== priceText ? fullPriceText : null,
                    ].filter(Boolean);
                    return (
                      <Fragment key={row.holding.id}>
                        <tr
                          className="cursor-pointer border-t border-border hover:bg-gray-50 dark:hover:bg-row-hover/40"
                          onClick={() => expanded.toggle(row.holding.id)}
                          aria-expanded={isOpen}
                        >
                          <td className="w-5 px-0.5 py-2 text-center text-xs text-gray-400" aria-hidden>{isOpen ? "▾" : "▸"}</td>
                          <td className="w-full py-2 pr-2 break-words">
                            <span className="font-medium">{row.holding.symbol}</span>
                            {row.holding.label && (
                              <span className="ml-1.5 text-gray-500 dark:text-gray-400" title={row.holding.label}>{row.holding.label}</span>
                            )}
                            <span className="ml-1.5 text-xs text-gray-400">{t(`portfolio.class.${row.holding.assetClass}`)}</span>
                            {row.holding.closed && (
                              <span className="ml-1.5 rounded-full bg-gray-200 px-2 py-0.5 text-xs text-gray-600 dark:bg-raised dark:text-gray-300">
                                {t("portfolio.closed")}
                              </span>
                            )}
                            <ProviderNotListedBadge holding={row.holding} locale={locale} />
                          </td>
                          <td
                            className="whitespace-nowrap px-1 text-right font-mono tabular-nums xl:px-1.5"
                            title={formatQuantityFull(row.holding.netQuantity, locale)}
                          >
                            {formatQuantity(row.holding.netQuantity, locale)}
                          </td>
                          <td className="whitespace-nowrap px-1 text-right font-mono tabular-nums xl:px-1.5">{money(row.holding.remainingCostBasis)}</td>
                          <td className={`whitespace-nowrap px-1 text-right font-mono tabular-nums xl:px-1.5 ${avg != null ? "align-top" : "align-middle"}`}>
                            {row.currentPrice != null ? (
                              <div className="flex flex-col items-end leading-tight">
                                <span
                                  className={row.stale ? "text-amber-600 dark:text-amber-400" : undefined}
                                  title={priceTitleParts.length > 0 ? priceTitleParts.join(" · ") : undefined}
                                >
                                  {/* Leads the number so stale rows keep their digits aligned with the rest. */}
                                  {age?.stale && <StalePriceDot age={age} locale={locale} className="mr-1.5" />}
                                  {priceText}
                                </span>
                                {avg != null && (
                                  <span
                                    className="text-xs font-normal text-gray-400 dark:text-gray-500"
                                    title={`${t("portfolio.avg_cost_tooltip")}: ${formatFullPrecisionMoney(avg, household.currency, locale)}`}
                                  >
                                    {t("portfolio.avg_cost_short")} {price(avg, household.currency, row.currentPrice)}
                                  </span>
                                )}
                              </div>
                            ) : (
                              <PriceBadge row={row} />
                            )}
                          </td>
                          <td className="whitespace-nowrap px-1 text-right font-mono font-medium tabular-nums xl:px-1.5">{money(row.currentValue)}</td>
                          <td className={`whitespace-nowrap px-1 text-right font-mono tabular-nums xl:px-1.5 ${toneClass(pnlTone(row.unrealizedPnl))}`}>
                            {row.unrealizedPnl != null ? (
                              <>
                                {signedMoney(row.unrealizedPnl, money(row.unrealizedPnl))}
                                {row.unrealizedPnlPct != null && (
                                  <span className="ml-1 text-xs">
                                    ({formatNumber(fractionToPercent(row.unrealizedPnlPct) ?? 0, locale, 1)}%)
                                  </span>
                                )}
                              </>
                            ) : "—"}
                          </td>
                          <td className={`whitespace-nowrap px-1 text-right font-mono tabular-nums xl:px-1.5 ${toneClass(pnlTone(row.realizedPnl))}`}>
                            {signedMoney(row.realizedPnl, money(row.realizedPnl))}
                          </td>
                          <td className="whitespace-nowrap px-1 text-right font-mono tabular-nums xl:px-1.5">
                            {percentLabel(weights.get(row.holding.id), locale)}
                          </td>
                          {telegramActive && (
                            <td className="w-10 whitespace-nowrap px-1 py-1 text-right" onClick={(e) => e.stopPropagation()}>
                              {isAlertEligibleHolding(row) && (
                                <AlertBellButton row={row} onClick={() => setAlertModal({ row })} />
                              )}
                            </td>
                          )}
                        </tr>
                        {isOpen && (
                          <tr className="border-t border-border bg-gray-50/50 dark:bg-sunken/30">
                            <td></td>
                            <td colSpan={telegramActive ? 9 : 8} className="px-2 py-3">
                              <HoldingDetail
                                row={row}
                                telegramActive={telegramActive}
                                onOpenAlert={() => setAlertModal({ row })}
                                onEdit={() => setPanel({ kind: "edit", holding: row.holding })}
                                onDelete={() => {
                                  if (window.confirm(t("common.delete") + "?")) void del.mutate(row.holding.id);
                                }}
                                onUnlink={() => void unlink.mutate(row.holding.id)}
                              />
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
              </div>
            </>
          )}
        </CardBody>
      </Card>

      {telegramActive && alertModal && (
        <PriceAlertModal
          householdId={household.householdId}
          holdingRow={alertModal.row}
          displayTimezone={displayTimezone}
          existing={alertModal.existing}
          onClose={() => setAlertModal(null)}
        />
      )}

      {telegramActive && alertsDrawerOpen && (
        <PriceAlertsDrawer
          householdId={household.householdId}
          holdings={holdings}
          displayTimezone={displayTimezone}
          onEdit={(alert, holdingRow) => {
            setAlertsDrawerOpen(false);
            setAlertModal({ row: holdingRow, existing: alert });
          }}
          onClose={() => setAlertsDrawerOpen(false)}
        />
      )}
    </div>
  );
}

function AlertBellButton({ row, onClick }: { row: HoldingSummary; onClick: () => void }) {
  const { t } = useTranslation();
  const count = row.activeAlertCount ?? 0;
  const label =
    count > 0
      ? t("portfolio.alerts.bell_active_label", { symbol: row.holding.symbol, count })
      : t("portfolio.alerts.bell_label", { symbol: row.holding.symbol });
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      aria-label={label}
      title={label}
      className={cn(
        "inline-flex items-center gap-0.5 rounded-md px-1 py-1 text-xs transition-colors hover:bg-gray-100 dark:hover:bg-raised",
        count > 0 ? "font-medium text-sky-600 dark:text-sky-400" : "text-gray-400 hover:text-gray-600 dark:text-gray-500 dark:hover:text-gray-300",
      )}
    >
      <span aria-hidden>🔔</span>
      {count > 0 && <span className="font-mono tabular-nums">{count}</span>}
    </button>
  );
}

/** A header that sorts the table by its own column. `aria-sort` carries the state for a screen reader, the
 *  caret for everyone else; the caret's slot is reserved so a click never nudges the header sideways. */
function SortableColumn({
  column,
  sort,
  onSort,
}: {
  column: HoldingColumn;
  sort: HoldingSort | null;
  onSort: (key: HoldingSortKey) => void;
}) {
  const { t } = useTranslation();
  const active = sort?.key === column.key;
  const label = t(column.labelKey);
  return (
    <th
      scope="col"
      className={cn("py-2", column.align === "right" && "text-right", column.className)}
      aria-sort={!active ? "none" : sort.direction === "asc" ? "ascending" : "descending"}
    >
      <button
        type="button"
        onClick={() => onSort(column.key)}
        title={t("common.sort_by", { column: label })}
        className={cn(
          "inline-flex items-center gap-0.5 hover:text-gray-700 dark:hover:text-gray-200",
          column.align === "right" && "justify-end text-right",
          active && "text-gray-700 dark:text-gray-200",
        )}
      >
        {label}
        <span aria-hidden className="w-2 text-[0.625rem] leading-none">
          {active ? (sort.direction === "asc" ? "▲" : "▼") : ""}
        </span>
      </button>
    </th>
  );
}

function Stat({
  label,
  value,
  tone,
  title,
  children,
}: {
  label: string;
  value: string;
  tone?: string;
  title?: string;
  children?: ReactNode;
}) {
  return (
    <Card>
      <CardBody className="py-3">
        <p className="text-xs text-gray-500 dark:text-gray-400">{label}</p>
        <p className={`mt-0.5 text-lg font-semibold ${tone ?? ""}`} title={title}>{value}</p>
        {children}
      </CardBody>
    </Card>
  );
}

/** The whole collapsed-row signal for a price past its class tolerance: one amber dot, with its date on
 *  hover. A fresh row shows nothing; the age in words lives in the expanded holding. */
function StalePriceDot({ age, locale, className }: { age: PriceAge; locale: string; className?: string }) {
  const { t } = useTranslation();
  return (
    <span
      role="img"
      aria-label={t("portfolio.stale_price")}
      title={t("portfolio.price_age_tooltip", { when: priceAsOfLabel(age, locale) })}
      className={cn("inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500 align-middle", className)}
    />
  );
}

/** The provider answered 404 for the linked symbol (delisted, renamed or mistyped); its stored history stays. */
function ProviderNotListedBadge({ holding, locale }: { holding: Holding; locale: string }) {
  const { t } = useTranslation();
  if (!holding.providerNotFoundSince) return null;
  return (
    <span
      className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-normal text-amber-800 dark:bg-amber-900/50 dark:text-amber-200"
      title={t("portfolio.provider_not_listed_tooltip", {
        symbol: holding.providerSymbol,
        date: formatDate(holding.providerNotFoundSince, locale),
      })}
    >
      {t("portfolio.provider_not_listed")}
    </span>
  );
}

function PriceBadge({ row }: { row: HoldingSummary }) {
  const { t } = useTranslation();
  if (row.holding.assetClass === "fund") {
    return <span className="text-xs text-gray-500 dark:text-gray-400">{t("portfolio.fund_no_provider")}</span>;
  }
  if (!row.holding.linked) {
    return <span className="text-xs text-amber-600">{t("portfolio.not_linked")}</span>;
  }
  if (row.currentPrice == null) {
    return <span className="text-xs text-amber-600">{t("portfolio.no_price")}</span>;
  }
  if (row.stale) {
    return <span className="text-xs text-amber-600">{t("portfolio.stale_price")}</span>;
  }
  return null;
}

function HoldingDetail({
  row,
  telegramActive,
  onOpenAlert,
  onEdit,
  onDelete,
  onUnlink,
}: {
  row: HoldingSummary;
  telegramActive: boolean;
  onOpenAlert: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onUnlink: () => void;
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const holding = row.holding;
  const locale = i18n.language;
  // Where the age lives in full, fresh or stale: the row is open, so there is room to say it
  // in words. Funds have no provider to date a price from — they are valued by hand.
  const age = priceAge(row, new Date());
  const freshness = age
    ? t("portfolio.price_from", { date: priceAsOfLabel(age, locale), age: formatPriceAge(age, locale) })
    : holding.assetClass === "fund"
      ? t("portfolio.price_manual")
      : null;
  const eligibleForAlert = telegramActive && isAlertEligibleHolding(row);
  const holdingAlertCount = row.activeAlertCount ?? 0;

  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-gray-500 dark:text-gray-400">
          {holding.linked
            ? t("portfolio.linked_to", { provider: holding.provider, symbol: holding.providerSymbol })
            : t("portfolio.not_linked")}
          {holding.isin && ` · ISIN ${holding.isin}`}
          {` · ${holding.nativeCurrency}`}
          {freshness && (
            <span className={age?.stale ? "text-amber-600 dark:text-amber-400" : undefined}> · {freshness}</span>
          )}
        </p>
        <div className="flex gap-1">
          <Button variant="ghost" className="px-2" aria-label={t("common.edit")} title={t("common.edit")} onClick={onEdit}>
            <span aria-hidden>✏️</span>
          </Button>
          {holding.linked && (
            <Button variant="ghost" className="px-2" onClick={onUnlink}>
              {t("portfolio.unlink")}
            </Button>
          )}
          <Button variant="ghost" className="px-2" aria-label={t("common.delete")} title={t("common.delete")} onClick={onDelete}>
            <span aria-hidden>🗑️</span>
          </Button>
        </div>
      </div>
      {eligibleForAlert && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-sunken px-3 py-2 text-xs">
          <span className="text-gray-700 dark:text-gray-300">
            <span className="mr-1.5" aria-hidden>🔔</span>
            {holdingAlertCount > 0
              ? t("portfolio.alerts.manage_for_holding_count", { count: holdingAlertCount })
              : t("portfolio.alerts.manage_for_holding")}
          </span>
          <Button variant="secondary" className="px-2.5 py-1 text-xs" onClick={onOpenAlert}>
            {t("portfolio.alerts.modal_title_create", { symbol: holding.symbol })}
          </Button>
        </div>
      )}
      {!holding.linked && holding.assetClass !== "fund" && <LinkPanel holding={holding} />}
      <LotsEditor holding={holding} currency={household.currency} locale={locale} />
    </div>
  );
}

function LinkPanel({ holding }: { holding: Holding }) {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const link = useLinkHolding(household.householdId);
  const [error, setError] = useState<string | null>(null);

  async function pick(candidate: SymbolCandidate) {
    setError(null);
    try {
      await link.mutateAsync({
        id: holding.id,
        input: {
          provider: candidate.provider,
          providerSymbol: candidate.providerSymbol,
          nativeCurrency: candidate.currency,
          isin: candidate.isin,
        },
      });
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  }

  return (
    <div className="max-w-md space-y-1">
      <Label>{t("portfolio.link_title")}</Label>
      <SymbolSearchCombobox assetClass={holding.assetClass} onSelect={(c) => void pick(c)} />
      <FieldError message={error} />
    </div>
  );
}

/** Which trade the one open form is about: a new one of a given type, or an existing lot being replaced. */
type LotFormTarget = { kind: "create"; type: LotType } | { kind: "edit"; lot: Lot };

function LotsEditor({ holding, currency, locale }: { holding: Holding; currency: string; locale: string }) {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const deleteLot = useDeleteLot(household.householdId);

  // One form at a time: opening an edit closes the add form and vice versa, so there is never a
  // second set of fields competing for the same ledger.
  const [target, setTarget] = useState<LotFormTarget | null>(null);
  const creating = target?.kind === "create" ? target.type : null;

  const sortedLots = useMemo(
    () => [...holding.lots].sort((a, b) => b.tradedOn.localeCompare(a.tradedOn)),
    [holding.lots]
  );

  function toggleCreate(type: LotType) {
    setTarget((current) => (current?.kind === "create" && current.type === type ? null : { kind: "create", type }));
  }

  function toggleEdit(lot: Lot) {
    setTarget((current) => (current?.kind === "edit" && current.lot.id === lot.id ? null : { kind: "edit", lot }));
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
          {t("portfolio.lots")}
        </p>
        <div className="flex gap-1">
          <Button variant="secondary" className="px-2 py-1 text-xs" onClick={() => toggleCreate("BUY")}>
            {creating === "BUY" ? t("common.cancel") : t("portfolio.add_lot")}
          </Button>
          <Button variant="secondary" className="px-2 py-1 text-xs" onClick={() => toggleCreate("SELL")}>
            {creating === "SELL" ? t("common.cancel") : t("portfolio.register_sale")}
          </Button>
        </div>
      </div>

      {target?.kind === "create" && (
        <div className="rounded-md border border-border p-3">
          <LotForm
            key={`create-${target.type}`}
            holding={holding}
            target={target}
            onClose={() => setTarget(null)}
          />
        </div>
      )}

      {sortedLots.length === 0 ? (
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("portfolio.no_lots")}</p>
      ) : (
        <ul className="space-y-1">
          {sortedLots.map((lot) => {
            const remaining = lot.remainingQty;
            // A BUY whose remaining quantity no longer matches what was bought has been (partly) sold.
            const partlySold = lot.type === "BUY" && remaining != null && !new Decimal(remaining).eq(lot.quantity);
            // Unrealized only makes sense for a BUY that still holds something and is priced.
            const showUnrealized =
              lot.type === "BUY" && remaining != null && new Decimal(remaining).gt(0) && lot.unrealizedPnl != null;
            const showRealized = lot.realizedPnl != null && pnlTone(lot.realizedPnl) !== "neutral";
            const editing = target?.kind === "edit" && target.lot.id === lot.id;
            return (
              <li key={lot.id} className="rounded border border-border px-2 py-1">
                <div className="flex items-start justify-between gap-2">
                  <span className="text-xs text-gray-600 dark:text-gray-300">
                    <span
                      className={cnBadge(lot.type)}
                    >
                      {t(`portfolio.lot_type.${lot.type}`)}
                    </span>
                    {" "}
                    {formatDate(lot.tradedOn, locale)} ·{" "}
                    <span title={formatQuantityFull(lot.quantity, locale)}>
                      {formatQuantity(lot.quantity, locale)}
                    </span>{" "}
                    ×{" "}
                    <span title={formatFullPrecisionMoney(lot.unitPrice, lot.currency, locale)}>
                      {formatPrice(lot.unitPrice, lot.currency, locale)}
                    </span>
                    {lot.fee && ` · ${t("portfolio.fee")} ${formatMoney(lot.fee, lot.currency, locale)}`}
                    {partlySold && (
                      <span
                        className="text-gray-400 dark:text-gray-500"
                        title={formatQuantityFull(remaining!, locale)}
                      >
                        {" "}
                        · {formatQuantity(remaining!, locale)} {t("portfolio.held_short")}
                      </span>
                    )}
                    {lot.note && ` · ${lot.note}`}
                  </span>
                  <span className="flex flex-col items-end gap-0.5">
                    <span className="flex items-center gap-1">
                      <span className="font-mono text-xs tabular-nums">{formatMoney(lot.amountBase, currency, locale)}</span>
                      <Button
                        variant="ghost"
                        className="px-1 py-0.5"
                        aria-label={t("portfolio.edit_lot")}
                        title={t("portfolio.edit_lot")}
                        onClick={() => toggleEdit(lot)}
                      >
                        <span aria-hidden>✏️</span>
                      </Button>
                      <Button
                        variant="ghost"
                        className="px-1 py-0.5"
                        aria-label={t("common.delete")}
                        title={t("common.delete")}
                        onClick={() => {
                          if (window.confirm(t("common.delete") + "?")) {
                            void deleteLot.mutate({ holdingId: holding.id, lotId: lot.id });
                          }
                        }}
                      >
                        <span aria-hidden>🗑️</span>
                      </Button>
                    </span>
                    {(showUnrealized || showRealized) && (
                      <span className="flex flex-wrap items-center justify-end gap-x-2 gap-y-0.5 text-[10px] font-medium">
                        {showUnrealized && (
                          <span className={toneClass(pnlTone(lot.unrealizedPnl))}>
                            {t("portfolio.unrealized_short")}: {signedMoney(lot.unrealizedPnl!, formatMoney(lot.unrealizedPnl!, currency, locale))}
                          </span>
                        )}
                        {showRealized && (
                          <span className={toneClass(pnlTone(lot.realizedPnl))}>
                            {t("portfolio.realized_short")}: {signedMoney(lot.realizedPnl!, formatMoney(lot.realizedPnl!, currency, locale))}
                          </span>
                        )}
                      </span>
                    )}
                  </span>
                </div>
                {/* The form opens inside the row it edits: on a long ledger the fields belong where the
                    pencil was clicked, not back at the top of the list. */}
                {editing && (
                  <div className="mt-2 border-t border-border pt-2">
                    <LotForm key={`edit-${lot.id}`} holding={holding} target={target} onClose={() => setTarget(null)} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** The trade form, for a new lot or an existing one. Editing is a wholesale replace validated by the
 *  server's ledger replay, so every field is editable — including the type. Remounted per target
 *  (`key`), which is what prefills it. */
function LotForm({
  holding,
  target,
  onClose,
}: {
  holding: Holding;
  target: LotFormTarget;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const idPrefix = useId();
  const household = useActiveHousehold();
  const addLot = useAddLot(household.householdId);
  const updateLot = useUpdateLot(household.householdId);
  const editing = target.kind === "edit" ? target.lot : null;

  const [type, setType] = useState<LotType>(target.kind === "edit" ? target.lot.type : target.type);
  const [tradedOn, setTradedOn] = useState(editing?.tradedOn ?? isoToday());
  const [quantity, setQuantity] = useState(editing ? trimZeroTail(editing.quantity) : "");
  const [unitPrice, setUnitPrice] = useState(editing ? trimZeroTail(editing.unitPrice) : "");
  const [totalPrice, setTotalPrice] = useState(editing ? computeTotalPrice(editing.quantity, editing.unitPrice) : "");
  // Remembers which price input drives recalculation when quantity changes.
  const [priceAnchor, setPriceAnchor] = useState<"unit" | "total">("unit");
  const [lotCurrency, setLotCurrency] = useState(editing?.currency ?? holding.nativeCurrency);
  const [fee, setFee] = useState(editing?.fee ? trimZeroTail(editing.fee) : "");
  const [note, setNote] = useState(editing?.note ?? "");
  const [error, setError] = useState<string | null>(null);

  function handleQuantityChange(nextQuantity: string) {
    setQuantity(nextQuantity);
    if (priceAnchor === "total") {
      setUnitPrice(computeUnitPrice(nextQuantity, totalPrice));
    } else {
      setTotalPrice(computeTotalPrice(nextQuantity, unitPrice));
    }
  }

  function handleUnitPriceChange(nextUnitPrice: string) {
    setPriceAnchor("unit");
    setUnitPrice(nextUnitPrice);
    setTotalPrice(computeTotalPrice(quantity, nextUnitPrice));
  }

  function handleTotalPriceChange(nextTotalPrice: string) {
    setPriceAnchor("total");
    setTotalPrice(nextTotalPrice);
    setUnitPrice(computeUnitPrice(quantity, nextTotalPrice));
  }

  // A mis-clicked type would otherwise change a buy into a sale in silence. Says so and nothing more:
  // the save still runs the same validation it would for a fresh trade.
  const converting = editing != null && type !== editing.type;

  async function save() {
    setError(null);
    if (!quantity || !unitPrice || !tradedOn) {
      setError(t("errors.field_required"));
      return;
    }
    const input = {
      type,
      tradedOn,
      quantity,
      unitPrice,
      currency: lotCurrency || null,
      fee: fee || null,
      note: note || null,
    };
    try {
      if (editing) await updateLot.mutateAsync({ holdingId: holding.id, lotId: editing.id, input });
      else await addLot.mutateAsync({ holdingId: holding.id, input });
      onClose();
    } catch (err) {
      // Stays open with the values as typed: a rejected edit is usually one field away from valid.
      setError(apiErrorMessage(err, t));
    }
  }

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium">
        {editing ? t("portfolio.edit_lot") : type === "BUY" ? t("portfolio.add_lot") : t("portfolio.register_sale")}
      </p>
      <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
        {editing && (
          <div>
            <Label htmlFor={`${idPrefix}-type`}>{t("portfolio.trade_type")}</Label>
            <Select id={`${idPrefix}-type`} value={type} onChange={(e) => setType(e.target.value as LotType)}>
              <option value="BUY">{t("portfolio.lot_type.BUY")}</option>
              <option value="SELL">{t("portfolio.lot_type.SELL")}</option>
            </Select>
          </div>
        )}
        <div>
          <Label htmlFor={`${idPrefix}-traded-on`}>{t("portfolio.traded_on")}</Label>
          <Input id={`${idPrefix}-traded-on`} type="date" value={tradedOn} onChange={(e) => setTradedOn(e.target.value)} />
        </div>
        <div>
          <Label htmlFor={`${idPrefix}-quantity`}>{t("portfolio.quantity")}</Label>
          <Input
            id={`${idPrefix}-quantity`}
            type="number"
            step="any"
            min="0"
            value={quantity}
            onChange={(e) => handleQuantityChange(e.target.value)}
          />
        </div>
        <div>
          <Label htmlFor={`${idPrefix}-unit-price`}>
            {type === "BUY" ? t("portfolio.unit_price") : t("portfolio.sale_price")}
          </Label>
          <Input
            id={`${idPrefix}-unit-price`}
            type="number"
            step="any"
            min="0"
            value={unitPrice}
            onChange={(e) => handleUnitPriceChange(e.target.value)}
          />
        </div>
        <div>
          <Label htmlFor={`${idPrefix}-total-price`}>{t("portfolio.total_price")}</Label>
          <Input
            id={`${idPrefix}-total-price`}
            type="number"
            step="any"
            min="0"
            value={totalPrice}
            onChange={(e) => handleTotalPriceChange(e.target.value)}
          />
        </div>
        <div>
          <Label htmlFor={`${idPrefix}-currency`}>{t("portfolio.lot_currency")}</Label>
          <Input
            id={`${idPrefix}-currency`}
            value={lotCurrency}
            maxLength={3}
            onChange={(e) => setLotCurrency(e.target.value.toUpperCase())}
          />
        </div>
        <div>
          <Label htmlFor={`${idPrefix}-fee`}>{t("portfolio.fee")}</Label>
          <Input
            id={`${idPrefix}-fee`}
            type="number"
            step="any"
            min="0"
            value={fee}
            onChange={(e) => setFee(e.target.value)}
          />
        </div>
        <div>
          <Label htmlFor={`${idPrefix}-note`}>{t("portfolio.note")}</Label>
          <Input id={`${idPrefix}-note`} value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
      </div>
      {converting && (
        <p className="text-xs text-amber-700 dark:text-amber-300">
          {t(type === "SELL" ? "portfolio.converting_to_sell" : "portfolio.converting_to_buy")}
        </p>
      )}
      <FieldError message={error} />
      <div className="flex justify-end gap-2">
        <Button variant="secondary" className="px-2 py-1 text-xs" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button onClick={() => void save()}>{t("common.save")}</Button>
      </div>
    </div>
  );
}

function cnBadge(type: LotType): string {
  return type === "BUY"
    ? "rounded bg-sky-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-sky-700 dark:bg-sky-900/40 dark:text-sky-300"
    : "rounded bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-amber-700 dark:bg-amber-900/40 dark:text-amber-300";
}

function HoldingFormPanel({
  editing,
  onClose,
  onCreate,
  onUpdate,
}: {
  editing: Holding | null;
  onClose: () => void;
  onCreate: (input: Parameters<ReturnType<typeof useCreateHolding>["mutateAsync"]>[0]) => Promise<unknown>;
  onUpdate: (id: string, input: Parameters<ReturnType<typeof useUpdateHolding>["mutateAsync"]>[0]["input"]) => Promise<unknown>;
}) {
  const { t } = useTranslation();
  const [assetClass, setAssetClass] = useState<HoldingAssetClass>(editing?.assetClass ?? "crypto");
  const [symbol, setSymbol] = useState(editing?.symbol ?? "");
  const [label, setLabel] = useState(editing?.label ?? "");
  const [nativeCurrency, setNativeCurrency] = useState(editing?.nativeCurrency ?? "EUR");
  const [isin, setIsin] = useState(editing?.isin ?? "");
  const [candidate, setCandidate] = useState<SymbolCandidate | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [symbolError, setSymbolError] = useState<string | null>(null);

  async function save() {
    setError(null);
    if (!symbol.trim()) {
      setSymbolError(t("errors.field_required"));
      return;
    }
    setSymbolError(null);
    try {
      if (editing) {
        await onUpdate(editing.id, {
          symbol: symbol.trim(),
          label: label || null,
          isin: isin || null,
          ...(editing.linked ? {} : { nativeCurrency: nativeCurrency || null }),
        });
      } else {
        await onCreate({
          assetClass,
          symbol: symbol.trim(),
          label: label || null,
          nativeCurrency: candidate?.currency ?? nativeCurrency ?? null,
          isin: (candidate?.isin ?? isin) || null,
          provider: candidate?.provider ?? null,
          providerSymbol: candidate?.providerSymbol ?? null,
        });
      }
      onClose();
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  }

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{editing ? t("common.edit") : t("portfolio.new_holding")}</p>
        {!editing && <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("portfolio.new_holding_hint")}</p>}
      </CardHeader>
      <CardBody className="space-y-3">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {!editing && (
            <div>
              <Label>{t("portfolio.asset_class")}</Label>
              <Select
                value={assetClass}
                onChange={(e) => {
                  setAssetClass(e.target.value as HoldingAssetClass);
                  setCandidate(null);
                }}
              >
                {ASSET_CLASSES.map((c) => (
                  <option key={c} value={c}>{t(`portfolio.class.${c}`)}</option>
                ))}
              </Select>
            </div>
          )}
          <div>
            <Label>{t("portfolio.symbol")}</Label>
            <Input
              value={symbol}
              invalid={!!symbolError}
              onChange={(e) => setSymbol(e.target.value.toUpperCase())}
            />
            <FieldError message={symbolError} />
          </div>
          <div>
            <Label>{t("portfolio.label")}</Label>
            <Input value={label} onChange={(e) => setLabel(e.target.value)} />
          </div>
          {(editing ? !editing.linked : !candidate) && (
            <div>
              <Label>{t("portfolio.native_currency")}</Label>
              <Input
                value={nativeCurrency}
                maxLength={3}
                onChange={(e) => setNativeCurrency(e.target.value.toUpperCase())}
              />
            </div>
          )}
          <div>
            <Label>ISIN</Label>
            <Input value={isin} maxLength={12} onChange={(e) => setIsin(e.target.value.toUpperCase())} />
          </div>
        </div>

        {!editing && assetClass !== "fund" && (
          <div>
            <Label>{t("portfolio.link_title")}</Label>
            <SymbolSearchCombobox
              assetClass={assetClass}
              onSelect={(c) => {
                setCandidate(c);
                if (!symbol) setSymbol(c.symbol ?? c.providerSymbol.toUpperCase());
                if (!label) setLabel(c.name);
              }}
            />
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
              {candidate
                ? t("portfolio.will_link", { provider: candidate.provider, symbol: candidate.providerSymbol })
                : t("portfolio.link_optional_hint")}
            </p>
          </div>
        )}
        {!editing && assetClass === "fund" && (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("portfolio.fund_no_provider")}</p>
        )}

        <FieldError message={error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button>
          <Button onClick={() => void save()}>{t("common.save")}</Button>
        </div>
      </CardBody>
    </Card>
  );
}
