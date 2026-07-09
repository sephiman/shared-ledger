import { Fragment, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useAddLot,
  useCreateHolding,
  useDeleteHolding,
  useDeleteLot,
  useLinkHolding,
  usePortfolioSummary,
  useUnlinkHolding,
  useUpdateHolding,
  type Holding,
  type HoldingAssetClass,
  type HoldingSummary,
  type LotType,
  type SymbolCandidate,
} from "@/api/portfolio";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { formatMoney, formatNumber, formatPrice } from "@/lib/money";
import { formatDate, isoToday } from "@/lib/dates";
import { SymbolSearchCombobox } from "./SymbolSearchCombobox";
import { fractionToPercent, percentOf, pnlTone, signedMoney } from "./valuation";

const ASSET_CLASSES: HoldingAssetClass[] = ["crypto", "etf", "stock", "fund"];

type PanelMode = { kind: "closed" } | { kind: "create" } | { kind: "edit"; holding: Holding };

/** Quantity at full stored precision: trims only pure trailing zeros (10000.000000 -> 10000),
 *  never drops meaningful digits, and avoids exponent notation for tiny holdings. */
function formatQty(value: string): string {
  try {
    return new Decimal(value).toFixed();
  } catch {
    return value;
  }
}

/** Average purchase cost per unit = remaining cost basis / net quantity, in base currency.
 *  Null when the position holds nothing (closed / zero quantity) so we skip the avg line.
 *  The quotient is bounded to 12 dp — the scale prices are stored at — so the division
 *  tail doesn't run to decimal.js's 20 significant digits; formatPrice then trims zeros. */
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
  totalValue: string;
  totalUnrealizedPnl: string | null;
  totalRealizedPnl: string;
  totalReturn: string | null;
}

/** Recomputes the totals for a subset of holdings, mirroring the backend summary math. */
function subtotals(rows: HoldingSummary[]): Totals {
  let cost = new Decimal(0);
  let value = new Decimal(0);
  let unrealized = new Decimal(0);
  let realized = new Decimal(0);
  let anyUnrealized = false;
  for (const r of rows) {
    cost = cost.plus(r.holding.remainingCostBasis || 0);
    if (r.currentValue != null) value = value.plus(r.currentValue);
    if (r.unrealizedPnl != null) {
      unrealized = unrealized.plus(r.unrealizedPnl);
      anyUnrealized = true;
    }
    realized = realized.plus(r.realizedPnl || 0);
  }
  return {
    totalCostBasis: cost.toString(),
    totalValue: value.toString(),
    totalUnrealizedPnl: anyUnrealized ? unrealized.toString() : null,
    totalRealizedPnl: realized.toString(),
    totalReturn: anyUnrealized ? unrealized.plus(realized).toString() : null,
  };
}

export function HoldingsTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: summary } = usePortfolioSummary(household.householdId);
  const create = useCreateHolding(household.householdId);
  const update = useUpdateHolding(household.householdId);
  const del = useDeleteHolding(household.householdId);
  const unlink = useUnlinkHolding(household.householdId);

  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [assetFilter, setAssetFilter] = useState<AssetFilter>("all");

  useEffect(() => {
    if (panel.kind === "edit") panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [panel]);

  function toggleExpanded(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const holdings = summary?.holdings ?? [];
  const availableClasses = ASSET_CLASSES.filter((c) => holdings.some((h) => h.holding.assetClass === c));
  const effectiveFilter: AssetFilter =
    assetFilter !== "all" && !availableClasses.includes(assetFilter) ? "all" : assetFilter;
  const filtered =
    effectiveFilter === "all" ? holdings : holdings.filter((h) => h.holding.assetClass === effectiveFilter);
  const totals: Totals | null = !summary
    ? null
    : effectiveFilter === "all"
      ? {
          totalCostBasis: summary.totalCostBasis,
          totalValue: summary.totalValue,
          totalUnrealizedPnl: summary.totalUnrealizedPnl,
          totalRealizedPnl: summary.totalRealizedPnl,
          totalReturn: summary.totalReturn,
        }
      : subtotals(filtered);
  const locale = i18n.language;
  const money = (v: string | number | null | undefined, currency = household.currency) =>
    v == null ? "—" : formatMoney(v, currency, locale);
  // Per-unit prices (current price, avg cost) at full precision — tiny coins keep every decimal.
  const price = (v: string | number | null | undefined, currency = household.currency) =>
    v == null ? "—" : formatPrice(v, currency, locale);
  // Signed money plus its percent of the cost basis, e.g. "+€1,234 (11.1%)".
  const pnlWithPct = (v: string | null | undefined, denom: string): string => {
    if (v == null) return "—";
    const pct = percentOf(v, denom);
    return `${signedMoney(v, money(v))}${pct != null ? ` (${formatNumber(pct, locale, 1)}%)` : ""}`;
  };

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("portfolio.description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/portfolio/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
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
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <Stat label={t("portfolio.invested")} value={money(totals.totalCostBasis)} />
          <Stat label={t("portfolio.current_value")} value={money(totals.totalValue)} />
          <Stat
            label={t("portfolio.unrealized_pnl")}
            value={pnlWithPct(totals.totalUnrealizedPnl, totals.totalCostBasis)}
            tone={toneClass(pnlTone(totals.totalUnrealizedPnl))}
          />
          <Stat
            label={t("portfolio.realized_pnl")}
            value={pnlWithPct(totals.totalRealizedPnl, totals.totalCostBasis)}
            tone={toneClass(pnlTone(totals.totalRealizedPnl))}
          />
          <Stat
            label={t("portfolio.total_return")}
            value={pnlWithPct(totals.totalReturn, totals.totalCostBasis)}
            tone={toneClass(pnlTone(totals.totalReturn))}
          />
        </div>
      )}

      {summary?.anyStale && (
        <div className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200">
          {t("portfolio.stale_prices_warning")}
        </div>
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
        <CardBody>
          {holdings.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("portfolio.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 lg:hidden">
                {filtered.map((row) => {
                  const isOpen = expandedIds.has(row.holding.id);
                  return (
                    <li key={row.holding.id} className="rounded-md border border-border dark:border-gray-700">
                      <button
                        type="button"
                        onClick={() => toggleExpanded(row.holding.id)}
                        aria-expanded={isOpen}
                        className="block w-full p-3 text-left"
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
                                <span className="ml-2 rounded-full bg-gray-200 px-2 py-0.5 text-xs font-normal text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                                  {t("portfolio.closed")}
                                </span>
                              )}
                            </p>
                            {row.holding.label && (
                              <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">{row.holding.label}</p>
                            )}
                            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                              {formatQty(row.holding.netQuantity)} · {t("portfolio.cost_basis")}: {money(row.holding.remainingCostBasis)}
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
                      {isOpen && (
                        <div className="border-t border-border px-3 py-2 dark:border-gray-700">
                          <HoldingDetail
                            row={row}
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
              <table className="w-full text-sm">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="w-6 py-2"></th>
                    <th>{t("portfolio.symbol")}</th>
                    <th className="text-right">{t("portfolio.quantity")}</th>
                    <th className="text-right">{t("portfolio.cost_basis")}</th>
                    <th className="min-w-[9.5rem] text-right">{t("portfolio.current_price")}</th>
                    <th className="text-right">{t("portfolio.current_value")}</th>
                    <th className="text-right">{t("portfolio.unrealized_pnl")}</th>
                    <th className="text-right">{t("portfolio.realized_pnl")}</th>
                    <th className="text-right">{t("portfolio.weight")}</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((row) => {
                    const isOpen = expandedIds.has(row.holding.id);
                    const avg = avgCost(row);
                    return (
                      <Fragment key={row.holding.id}>
                        <tr
                          className="cursor-pointer border-t border-border hover:bg-gray-50 dark:hover:bg-gray-700/40"
                          onClick={() => toggleExpanded(row.holding.id)}
                          aria-expanded={isOpen}
                        >
                          <td className="py-2 text-center text-xs text-gray-400" aria-hidden>{isOpen ? "▾" : "▸"}</td>
                          <td className="py-2">
                            <span className="font-medium">{row.holding.symbol}</span>
                            {row.holding.label && (
                              <span className="ml-2 text-gray-500 dark:text-gray-400">{row.holding.label}</span>
                            )}
                            <span className="ml-2 text-xs text-gray-400">{t(`portfolio.class.${row.holding.assetClass}`)}</span>
                            {row.holding.closed && (
                              <span className="ml-2 rounded-full bg-gray-200 px-2 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                                {t("portfolio.closed")}
                              </span>
                            )}
                          </td>
                          <td className="whitespace-nowrap text-right font-mono tabular-nums">{formatQty(row.holding.netQuantity)}</td>
                          <td className="whitespace-nowrap text-right font-mono tabular-nums">{money(row.holding.remainingCostBasis)}</td>
                          <td className={`whitespace-nowrap text-right font-mono tabular-nums ${avg != null ? "align-top" : "align-middle"}`}>
                            {row.currentPrice != null ? (
                              <div className="flex flex-col items-end leading-tight">
                                <span
                                  className={row.stale ? "text-amber-600 dark:text-amber-400" : undefined}
                                  title={
                                    row.priceAsOf
                                      ? t("portfolio.price_as_of", { date: formatDate(row.priceAsOf, locale) }) +
                                        (row.stale ? ` · ${t("portfolio.stale_price")}` : "")
                                      : undefined
                                  }
                                >
                                  {price(row.currentPrice, row.priceCurrency ?? row.holding.nativeCurrency)}
                                </span>
                                {avg != null && (
                                  <span
                                    className="text-xs font-normal text-gray-400 dark:text-gray-500"
                                    title={t("portfolio.avg_cost_tooltip")}
                                  >
                                    {t("portfolio.avg_cost_short")} {price(avg)}
                                  </span>
                                )}
                              </div>
                            ) : (
                              <PriceBadge row={row} />
                            )}
                          </td>
                          <td className="whitespace-nowrap text-right font-mono font-medium tabular-nums">{money(row.currentValue)}</td>
                          <td className={`whitespace-nowrap text-right font-mono tabular-nums ${toneClass(pnlTone(row.unrealizedPnl))}`}>
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
                          <td className={`whitespace-nowrap text-right font-mono tabular-nums ${toneClass(pnlTone(row.realizedPnl))}`}>
                            {signedMoney(row.realizedPnl, money(row.realizedPnl))}
                          </td>
                          <td className="whitespace-nowrap text-right font-mono tabular-nums">
                            {row.weight != null ? `${formatNumber(fractionToPercent(row.weight) ?? 0, locale, 1)}%` : "—"}
                          </td>
                        </tr>
                        {isOpen && (
                          <tr className="border-t border-border bg-gray-50/50 dark:bg-gray-900/30">
                            <td></td>
                            <td colSpan={8} className="px-2 py-3">
                              <HoldingDetail
                                row={row}
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
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <Card>
      <CardBody className="py-3">
        <p className="text-xs text-gray-500 dark:text-gray-400">{label}</p>
        <p className={`mt-0.5 text-lg font-semibold ${tone ?? ""}`}>{value}</p>
      </CardBody>
    </Card>
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
  onEdit,
  onDelete,
  onUnlink,
}: {
  row: HoldingSummary;
  onEdit: () => void;
  onDelete: () => void;
  onUnlink: () => void;
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const holding = row.holding;

  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-gray-500 dark:text-gray-400">
          {holding.linked
            ? t("portfolio.linked_to", { provider: holding.provider, symbol: holding.providerSymbol })
            : t("portfolio.not_linked")}
          {holding.isin && ` · ISIN ${holding.isin}`}
          {` · ${holding.nativeCurrency}`}
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
      {!holding.linked && holding.assetClass !== "fund" && <LinkPanel holding={holding} />}
      <LotsEditor holding={holding} currency={household.currency} locale={i18n.language} />
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
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
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

function LotsEditor({ holding, currency, locale }: { holding: Holding; currency: string; locale: string }) {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const addLot = useAddLot(household.householdId);
  const deleteLot = useDeleteLot(household.householdId);

  const [formType, setFormType] = useState<LotType | null>(null);
  const [tradedOn, setTradedOn] = useState(isoToday());
  const [quantity, setQuantity] = useState("");
  const [unitPrice, setUnitPrice] = useState("");
  const [lotCurrency, setLotCurrency] = useState(holding.nativeCurrency);
  const [fee, setFee] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  function openForm(type: LotType) {
    setFormType((current) => (current === type ? null : type));
    setError(null);
  }

  async function saveLot() {
    if (!formType) return;
    setError(null);
    if (!quantity || !unitPrice || !tradedOn) {
      setError(t("errors.field_required"));
      return;
    }
    try {
      await addLot.mutateAsync({
        holdingId: holding.id,
        input: {
          type: formType,
          tradedOn,
          quantity,
          unitPrice,
          currency: lotCurrency || null,
          fee: fee || null,
          note: note || null,
        },
      });
      setFormType(null);
      setQuantity("");
      setUnitPrice("");
      setFee("");
      setNote("");
    } catch (err) {
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
          {t("portfolio.lots")}
        </p>
        <div className="flex gap-1">
          <Button variant="secondary" className="px-2 py-1 text-xs" onClick={() => openForm("BUY")}>
            {formType === "BUY" ? t("common.cancel") : t("portfolio.add_lot")}
          </Button>
          <Button variant="secondary" className="px-2 py-1 text-xs" onClick={() => openForm("SELL")}>
            {formType === "SELL" ? t("common.cancel") : t("portfolio.register_sale")}
          </Button>
        </div>
      </div>

      {formType && (
        <div className="space-y-2 rounded-md border border-border p-3 dark:border-gray-700">
          <p className="text-xs font-medium">
            {formType === "BUY" ? t("portfolio.add_lot") : t("portfolio.register_sale")}
          </p>
          <div className="grid grid-cols-2 gap-2 md:grid-cols-3">
            <div>
              <Label>{t("portfolio.traded_on")}</Label>
              <Input type="date" value={tradedOn} onChange={(e) => setTradedOn(e.target.value)} />
            </div>
            <div>
              <Label>{t("portfolio.quantity")}</Label>
              <Input type="number" step="any" min="0" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
            </div>
            <div>
              <Label>{formType === "BUY" ? t("portfolio.unit_price") : t("portfolio.sale_price")}</Label>
              <Input type="number" step="any" min="0" value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} />
            </div>
            <div>
              <Label>{t("portfolio.lot_currency")}</Label>
              <Input value={lotCurrency} maxLength={3} onChange={(e) => setLotCurrency(e.target.value.toUpperCase())} />
            </div>
            <div>
              <Label>{t("portfolio.fee")}</Label>
              <Input type="number" step="any" min="0" value={fee} onChange={(e) => setFee(e.target.value)} />
            </div>
            <div>
              <Label>{t("portfolio.note")}</Label>
              <Input value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
          </div>
          <FieldError message={error} />
          <div className="flex justify-end">
            <Button onClick={() => void saveLot()}>{t("common.save")}</Button>
          </div>
        </div>
      )}

      {holding.lots.length === 0 ? (
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("portfolio.no_lots")}</p>
      ) : (
        <ul className="space-y-1">
          {holding.lots.map((lot) => {
            const remaining = lot.remainingQty;
            // A BUY whose remaining quantity no longer matches what was bought has been (partly) sold.
            const partlySold = lot.type === "BUY" && remaining != null && !new Decimal(remaining).eq(lot.quantity);
            // Unrealized only makes sense for a BUY that still holds something and is priced.
            const showUnrealized =
              lot.type === "BUY" && remaining != null && new Decimal(remaining).gt(0) && lot.unrealizedPnl != null;
            const showRealized = lot.realizedPnl != null && pnlTone(lot.realizedPnl) !== "neutral";
            return (
              <li key={lot.id} className="flex items-start justify-between gap-2 rounded border border-border px-2 py-1 dark:border-gray-700">
                <span className="text-xs text-gray-600 dark:text-gray-300">
                  <span
                    className={cnBadge(lot.type)}
                  >
                    {t(`portfolio.lot_type.${lot.type}`)}
                  </span>
                  {" "}
                  {formatDate(lot.tradedOn, locale)} · {formatQty(lot.quantity)} × {formatPrice(lot.unitPrice, lot.currency, locale)}
                  {lot.fee && ` · ${t("portfolio.fee")} ${formatMoney(lot.fee, lot.currency, locale)}`}
                  {partlySold && (
                    <span className="text-gray-400 dark:text-gray-500"> · {formatQty(remaining!)} {t("portfolio.held_short")}</span>
                  )}
                  {lot.note && ` · ${lot.note}`}
                </span>
                <span className="flex flex-col items-end gap-0.5">
                  <span className="flex items-center gap-1">
                    <span className="font-mono text-xs tabular-nums">{formatMoney(lot.amountBase, currency, locale)}</span>
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
              </li>
            );
          })}
        </ul>
      )}
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
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
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
