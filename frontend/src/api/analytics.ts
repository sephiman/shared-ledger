import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface GroupTotal {
  groupCode: string;
  amount: string;
}

export interface MonthDashboard {
  year: number;
  month: number;
  income: string;
  expenses: string;
  savings: string;
  savingsRate: number;
  byGroup: GroupTotal[];
}

export interface YearDashboard {
  year: number;
  income: string;
  expenses: string;
  savings: string;
  savingsRate: number;
  byGroup: GroupTotal[];
}

export interface YearOverYearResponse {
  month: number;
  years: number[];
  incomeByYear: Record<string, string>;
  expensesByYear: Record<string, string>;
  savingsRateByYear: Record<string, number>;
  categories: { categoryCode: string; perYear: Record<string, string> }[];
}

export interface YearByYearSeries {
  year: number;
  incomePerMonth: string[];
  expensesPerMonth: string[];
  savingsPerMonth: string[];
  savingsRatePerMonth: number[];
}

export interface YearByYearResponse {
  series: YearByYearSeries[];
}

export interface TrailingPoint {
  year: number;
  month: number;
  income: string;
  expenses: string;
  savingsRate: number;
}

export interface ForecastResponse {
  horizonMonths: number;
  windowMonths: number;
  categories: {
    categoryCode: string;
    historical: { year: number; month: number; amount: string }[];
    projection: { year: number; month: number; projectedExpense: string; source: string }[];
  }[];
}

export function useMonthDashboard(householdId: string, year: number, month: number) {
  return useQuery({
    queryKey: ["analytics", householdId, "month", year, month],
    queryFn: async () =>
      (await apiClient.get<MonthDashboard>(`/households/${householdId}/analytics/month`, { params: { year, month } })).data,
  });
}

export function useYearDashboard(householdId: string, year: number) {
  return useQuery({
    queryKey: ["analytics", householdId, "year", year],
    queryFn: async () =>
      (await apiClient.get<YearDashboard>(`/households/${householdId}/analytics/year`, { params: { year } })).data,
  });
}

export function useYearOverYear(householdId: string, month: number, years = 5) {
  return useQuery({
    queryKey: ["analytics", householdId, "yoy", month, years],
    queryFn: async () =>
      (await apiClient.get<YearOverYearResponse>(`/households/${householdId}/analytics/year-over-year`, { params: { month, years } })).data,
  });
}

export function useYearByYear(householdId: string, years: number[]) {
  return useQuery({
    queryKey: ["analytics", householdId, "yby", years],
    queryFn: async () =>
      (await apiClient.get<YearByYearResponse>(`/households/${householdId}/analytics/year-by-year`, {
        params: { years: years.join(",") },
        paramsSerializer: (p) => `years=${(p.years as string)}`,
      })).data,
    enabled: years.length > 0,
  });
}

export function useTrailing12(householdId: string) {
  return useQuery({
    queryKey: ["analytics", householdId, "trailing"],
    queryFn: async () => (await apiClient.get<{ points: TrailingPoint[] }>(`/households/${householdId}/analytics/trailing-12`)).data,
  });
}

export function useForecast(householdId: string, horizon: number, window: number) {
  return useQuery({
    queryKey: ["analytics", householdId, "forecast", horizon, window],
    queryFn: async () =>
      (await apiClient.get<ForecastResponse>(`/households/${householdId}/analytics/forecast`, {
        params: { horizonMonths: horizon, windowMonths: window },
      })).data,
  });
}

export interface SavingsRateBlock {
  rate: number;
  income: string;
  expenses: string;
}

export interface TrailingSparklinePoint {
  year: number;
  month: number;
  rate: number;
}

export interface FixedCostBlock {
  monthlyAverage: string;
  perDay: string;
  perYear: string;
}

export interface DashboardExtras {
  asOfYear: number;
  asOfMonth: number;
  trailing12: SavingsRateBlock;
  ytd: SavingsRateBlock;
  currentMonth: SavingsRateBlock;
  sparkline: TrailingSparklinePoint[];
  fixedRecurring: FixedCostBlock;
  fixedAll: FixedCostBlock;
  monthsAvailable: number;
}

export interface AllocationSlice {
  groupCode: string;
  amount: string;
  percentOfIncome: number;
}

export interface AllocationResponse {
  year: number;
  month: number | null;
  income: string;
  expenses: string;
  saved: string;
  slices: AllocationSlice[];
}

export interface MoverRow {
  categoryCode: string;
  groupCode: string | null;
  periodAmount: string;
  baselineAmount: string;
  deltaAbs: string;
  deltaPct: number | null;
}

export interface TopMoversResponse {
  year: number;
  month: number;
  baseline: "year_ago" | "trailing6_avg";
  increases: MoverRow[];
  decreases: MoverRow[];
  newActivity: MoverRow[];
  totalIncrease: string;
  totalDecrease: string;
}

export interface RecurringShareResponse {
  scope: "month" | "trailing12" | "ytd" | "year";
  year: number | null;
  month: number | null;
  recurring: string;
  discretionary: string;
  total: string;
  recurringShare: number;
  discretionaryShare: number;
}

export interface HeatmapMonth { year: number; month: number; }

export interface HeatmapCategoryRow {
  categoryCode: string;
  groupCode: string | null;
  values: (string | null)[];
}

export interface HeatmapResponse {
  direction: "expense" | "income";
  months: HeatmapMonth[];
  categories: HeatmapCategoryRow[];
}

export interface ContributionPoint {
  snapshotDate: string;
  cumulativeContribution: string;
  cumulativeWithdrawal: string;
  netContribution: string;
}

export interface ContributionSeriesResponse {
  hasMovements: boolean;
  points: ContributionPoint[];
}

export function useDashboardExtras(householdId: string) {
  return useQuery({
    queryKey: ["analytics", householdId, "dashboard-extras"],
    queryFn: async () =>
      (await apiClient.get<DashboardExtras>(`/households/${householdId}/analytics/dashboard-extras`)).data,
  });
}

export function useAllocation(householdId: string, year: number, month: number | null, enabled = true) {
  return useQuery({
    queryKey: ["analytics", householdId, "allocation", year, month],
    queryFn: async () =>
      (await apiClient.get<AllocationResponse>(`/households/${householdId}/analytics/allocation`, {
        params: month != null ? { year, month } : { year },
      })).data,
    enabled,
  });
}

export function useTopMovers(
  householdId: string,
  year: number,
  month: number,
  baseline: "year_ago" | "trailing6_avg",
  enabled = true,
) {
  return useQuery({
    queryKey: ["analytics", householdId, "top-movers", year, month, baseline],
    queryFn: async () =>
      (await apiClient.get<TopMoversResponse>(`/households/${householdId}/analytics/top-movers`, {
        params: { year, month, baseline },
      })).data,
    enabled,
  });
}

export interface RecurringShareParams {
  scope: "month" | "trailing12" | "ytd" | "year";
  year?: number;
  month?: number;
}

export function useRecurringShare(householdId: string, params: RecurringShareParams, enabled = true) {
  return useQuery({
    queryKey: ["analytics", householdId, "recurring-share", params],
    queryFn: async () =>
      (await apiClient.get<RecurringShareResponse>(`/households/${householdId}/analytics/recurring-share`, {
        params,
      })).data,
    enabled,
  });
}

export function useHeatmap(
  householdId: string,
  months: number,
  direction: "expense" | "income",
  enabled = true,
) {
  return useQuery({
    queryKey: ["analytics", householdId, "heatmap", months, direction],
    queryFn: async () =>
      (await apiClient.get<HeatmapResponse>(`/households/${householdId}/analytics/heatmap`, {
        params: { months, direction },
      })).data,
    enabled,
  });
}

export function useContributionSeries(householdId: string) {
  return useQuery({
    queryKey: ["analytics", householdId, "contribution-series"],
    queryFn: async () =>
      (await apiClient.get<ContributionSeriesResponse>(`/households/${householdId}/analytics/contribution-series`)).data,
  });
}

export interface CostOfLivingCategoryRow {
  categoryCode: string;
  groupCode: string | null;
  monthlyAverage: string;
}

export interface CostOfLivingResponse {
  asOfYear: number;
  asOfMonth: number;
  monthsAvailable: number;
  essentialMonthlyAverage: string;
  nonEssentialMonthlyAverage: string;
  totalMonthlyAverage: string;
  essentialPerYear: string;
  nonEssentialPerYear: string;
  totalPerYear: string;
  essentialShare: number;
  essentialCategories: CostOfLivingCategoryRow[];
  nonEssentialCategories: CostOfLivingCategoryRow[];
}

export function useCostOfLiving(householdId: string) {
  return useQuery({
    queryKey: ["analytics", householdId, "cost-of-living"],
    queryFn: async () =>
      (await apiClient.get<CostOfLivingResponse>(`/households/${householdId}/analytics/cost-of-living`)).data,
  });
}

export function useYearsAvailable(householdId: string) {
  return useQuery({
    queryKey: ["analytics", householdId, "years-available"],
    queryFn: async () =>
      (await apiClient.get<{ years: number[] }>(`/households/${householdId}/analytics/years-available`)).data,
    staleTime: 1000 * 60 * 5,
  });
}
