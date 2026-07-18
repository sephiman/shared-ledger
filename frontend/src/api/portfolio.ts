import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type HoldingAssetClass = "crypto" | "etf" | "stock" | "fund";
export type HoldingProvider = "coingecko" | "yahoo" | "eodhd" | "twelvedata";

export type LotType = "BUY" | "SELL";

export interface Lot {
  id: string;
  type: LotType;
  tradedOn: string;
  quantity: string;
  unitPrice: string;
  currency: string;
  fee: string | null;
  fxRateToBase: string;
  note: string | null;
  // Cost for a BUY, proceeds for a SELL, in base currency.
  amountBase: string;
  // Per-lot FIFO breakdown in base currency (holding-level view only).
  // BUY: quantity of this lot still held after later sells; null for SELL.
  remainingQty: string | null;
  // BUY: base cost basis of the remaining quantity; null for SELL.
  remainingCostBasis: string | null;
  // BUY: realized gain on the already-sold portion (at sale prices). SELL: the sale's realized P&L.
  realizedPnl: string | null;
  // BUY only: unrealized P&L on the remaining quantity at the current price; null when unpriced.
  unrealizedPnl: string | null;
}

export interface Holding {
  id: string;
  assetClass: HoldingAssetClass;
  symbol: string;
  label: string | null;
  nativeCurrency: string;
  isin: string | null;
  provider: HoldingProvider | null;
  providerSymbol: string | null;
  linked: boolean;
  active: boolean;
  lots: Lot[];
  netQuantity: string;
  remainingCostBasis: string;
  realizedPnl: string;
  closed: boolean;
  createdAt: string;
}

export interface HoldingInput {
  assetClass: HoldingAssetClass;
  symbol: string;
  label?: string | null;
  nativeCurrency?: string | null;
  isin?: string | null;
  provider?: HoldingProvider | null;
  providerSymbol?: string | null;
}

export interface HoldingUpdateInput {
  symbol?: string;
  label?: string | null;
  nativeCurrency?: string | null;
  isin?: string | null;
  active?: boolean;
}

export interface LotInput {
  type?: LotType;
  tradedOn: string;
  quantity: string;
  unitPrice: string;
  currency?: string | null;
  fee?: string | null;
  note?: string | null;
}

export interface LinkInput {
  provider: HoldingProvider;
  providerSymbol: string;
  nativeCurrency?: string | null;
  isin?: string | null;
}

export interface SymbolCandidate {
  provider: HoldingProvider;
  providerSymbol: string;
  name: string;
  symbol: string | null;
  currency: string | null;
  exchange: string | null;
  isin: string | null;
}

export interface HoldingSummary {
  holding: Holding;
  currentPrice: string | null;
  priceCurrency: string | null;
  priceAsOf: string | null;
  stale: boolean;
  currentValue: string | null;
  unrealizedPnl: string | null;
  unrealizedPnlPct: string | null;
  realizedPnl: string;
  // FIFO cost basis of this holding's sold lots, over the whole history.
  soldCostBasis: string;
  totalReturn: string | null;
  weight: string | null;
}

export interface PortfolioSummary {
  asOfDate: string;
  holdings: HoldingSummary[];
  // Remaining (FIFO-unconsumed) cost basis of open positions.
  totalCostBasis: string;
  totalValue: string;
  totalRealizedPnl: string;
  totalUnrealizedPnl: string | null;
  totalReturn: string | null;
  // FIFO cost basis of all sold lots, whole history.
  totalSoldCostBasis: string;
  // Fractions (0.1234 = +12.34 %); null when the denominator is zero or the numerator unknown.
  // Denominators: open cost basis / sold cost basis / their sum (capital deployed).
  unrealizedPnlPct: string | null;
  realizedPnlPct: string | null;
  totalReturnPct: string | null;
  byClass: Record<string, string>;
  anyStale: boolean;
  anyUnpriced: boolean;
}

export interface HoldingValuationRow {
  holdingId: string;
  assetClass: HoldingAssetClass;
  symbol: string;
  label: string | null;
  quantity: string;
  unitPrice: string | null;
  priceCurrency: string | null;
  priceAsOf: string | null;
  fxRate: string | null;
  valueBase: string;
  stale: boolean;
}

export interface PortfolioValuation {
  date: string;
  byClass: Record<string, string>;
  holdings: HoldingValuationRow[];
  anyStale: boolean;
}

export interface PortfolioEvolutionPoint {
  date: string;
  value: string;
  invested: string;
  realizedPnl: string;
  unrealizedPnl: string;
  // Cumulative time-weighted return since the range start, as a fraction (0.1234 = +12.34 %).
  twrPct: string;
}

export interface PortfolioEvolution {
  points: PortfolioEvolutionPoint[];
}

function invalidatePortfolio(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  void qc.invalidateQueries({ queryKey: ["portfolio", householdId] });
}

export function usePortfolioSummary(householdId: string) {
  return useQuery({
    queryKey: ["portfolio", householdId, "summary"],
    queryFn: async () =>
      (await apiClient.get<PortfolioSummary>(`/households/${householdId}/portfolio/summary`)).data,
  });
}

export function usePortfolioValuation(householdId: string, date: string, enabled = true) {
  return useQuery({
    queryKey: ["portfolio", householdId, "valuation", date],
    queryFn: async () =>
      (await apiClient.get<PortfolioValuation>(`/households/${householdId}/portfolio/valuation`, {
        params: { date },
      })).data,
    enabled: enabled && !!date,
  });
}

export interface EvolutionFilters {
  from?: string;
  to?: string;
  assetClass?: HoldingAssetClass;
  holdingId?: string;
}

export function usePortfolioEvolution(householdId: string, filters: EvolutionFilters = {}) {
  return useQuery({
    queryKey: ["portfolio", householdId, "evolution", filters],
    queryFn: async () =>
      (await apiClient.get<PortfolioEvolution>(`/households/${householdId}/portfolio/evolution`, {
        params: filters,
      })).data,
  });
}

export function useSymbolSearch(householdId: string, assetClass: HoldingAssetClass, query: string) {
  return useQuery({
    queryKey: ["portfolio", householdId, "symbol-search", assetClass, query],
    queryFn: async () =>
      (await apiClient.get<SymbolCandidate[]>(`/households/${householdId}/portfolio/symbol-search`, {
        params: { assetClass, q: query },
      })).data,
    enabled: query.trim().length >= 2 && assetClass !== "fund",
    staleTime: 1000 * 60 * 5,
  });
}

export function useCreateHolding(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: HoldingInput) =>
      (await apiClient.post<Holding>(`/households/${householdId}/portfolio/holdings`, input)).data,
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}

export function useUpdateHolding(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: HoldingUpdateInput }) =>
      (await apiClient.patch<Holding>(`/households/${householdId}/portfolio/holdings/${id}`, input)).data,
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}

export function useDeleteHolding(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/portfolio/holdings/${id}`);
    },
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}

export function useLinkHolding(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: LinkInput }) =>
      (await apiClient.post<Holding>(`/households/${householdId}/portfolio/holdings/${id}/link`, input)).data,
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}

export function useUnlinkHolding(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<Holding>(`/households/${householdId}/portfolio/holdings/${id}/unlink`)).data,
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}

export function useAddLot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ holdingId, input }: { holdingId: string; input: LotInput }) =>
      (await apiClient.post<Lot>(`/households/${householdId}/portfolio/holdings/${holdingId}/lots`, input)).data,
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}

export function useDeleteLot(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ holdingId, lotId }: { holdingId: string; lotId: string }) => {
      await apiClient.delete(`/households/${householdId}/portfolio/holdings/${holdingId}/lots/${lotId}`);
    },
    onSuccess: () => invalidatePortfolio(qc, householdId),
  });
}
