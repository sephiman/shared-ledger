package com.sharedledger.analytics

import com.fasterxml.jackson.annotation.JsonFormat
import java.math.BigDecimal

data class MonthDashboardResponse(
    val year: Int,
    val month: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val income: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expenses: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val savings: BigDecimal,
    val savingsRate: Double,
    val byGroup: List<GroupTotal>,
)

data class YearDashboardResponse(
    val year: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val income: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expenses: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val savings: BigDecimal,
    val savingsRate: Double,
    val byGroup: List<GroupTotal>,
)

data class GroupTotal(
    val groupCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
)

data class CategoryBreakdownRow(
    val categoryCode: String,
    val perYear: Map<Int, BigDecimal>,
)

data class YearOverYearResponse(
    val month: Int,
    val years: List<Int>,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val incomeByYear: Map<Int, BigDecimal>,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expensesByYear: Map<Int, BigDecimal>,
    val savingsRateByYear: Map<Int, Double>,
    val categories: List<CategoryBreakdownRow>,
)

data class YearByYearSeries(
    val year: Int,
    val incomePerMonth: List<BigDecimal>,
    val expensesPerMonth: List<BigDecimal>,
    val savingsPerMonth: List<BigDecimal>,
    val savingsRatePerMonth: List<Double>,
)

data class YearByYearResponse(
    val series: List<YearByYearSeries>,
)

data class TrailingPoint(
    val year: Int,
    val month: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val income: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expenses: BigDecimal,
    val savingsRate: Double,
)

data class TrailingResponse(val points: List<TrailingPoint>)

data class ForecastPoint(
    val year: Int,
    val month: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val projectedExpense: BigDecimal,
    val source: String, // "history" or "recurring"
)

data class CategoryForecast(
    val categoryCode: String,
    val historical: List<HistoricalPoint>,
    val projection: List<ForecastPoint>,
)

data class HistoricalPoint(
    val year: Int,
    val month: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
)

data class ForecastResponse(
    val horizonMonths: Int,
    val windowMonths: Int,
    val categories: List<CategoryForecast>,
)

data class SavingsRateBlock(
    val rate: Double,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val income: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expenses: BigDecimal,
)

data class TrailingSparklinePoint(
    val year: Int,
    val month: Int,
    val rate: Double,
)

data class FixedCostBlock(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyAverage: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val perDay: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val perYear: BigDecimal,
)

data class DashboardExtrasResponse(
    val asOfYear: Int,
    val asOfMonth: Int,
    val trailing12: SavingsRateBlock,
    val ytd: SavingsRateBlock,
    val currentMonth: SavingsRateBlock,
    val sparkline: List<TrailingSparklinePoint>,
    val fixedRecurring: FixedCostBlock,
    val fixedAll: FixedCostBlock,
    val monthsAvailable: Int,
)

data class AllocationSlice(
    val groupCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    val percentOfIncome: Double,
)

data class AllocationResponse(
    val year: Int,
    val month: Int?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val income: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expenses: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val saved: BigDecimal,
    val slices: List<AllocationSlice>,
)

data class MoverRow(
    val categoryCode: String,
    val groupCode: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val periodAmount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val baselineAmount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val deltaAbs: BigDecimal,
    val deltaPct: Double?,
)

data class TopMoversResponse(
    val year: Int,
    val month: Int,
    val baseline: String,
    val increases: List<MoverRow>,
    val decreases: List<MoverRow>,
    val newActivity: List<MoverRow>,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalIncrease: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalDecrease: BigDecimal,
)

data class RecurringShareResponse(
    val scope: String,
    val year: Int?,
    val month: Int?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val recurring: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val discretionary: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val total: BigDecimal,
    val recurringShare: Double,
    val discretionaryShare: Double,
)

data class HeatmapMonth(val year: Int, val month: Int)

data class HeatmapCategoryRow(
    val categoryCode: String,
    val groupCode: String?,
    val values: List<BigDecimal?>,
)

data class HeatmapResponse(
    val direction: String,
    val months: List<HeatmapMonth>,
    val categories: List<HeatmapCategoryRow>,
)

data class ContributionPoint(
    val snapshotDate: java.time.LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val cumulativeContribution: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val cumulativeWithdrawal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val netContribution: BigDecimal,
)

data class ContributionSeriesResponse(
    val hasMovements: Boolean,
    val points: List<ContributionPoint>,
)

data class YearsAvailableResponse(val years: List<Int>)

data class CostOfLivingCategoryRow(
    val categoryCode: String,
    val groupCode: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyAverage: BigDecimal,
)

data class CostOfLivingResponse(
    val asOfYear: Int,
    val asOfMonth: Int,
    val monthsAvailable: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val essentialMonthlyAverage: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val nonEssentialMonthlyAverage: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalMonthlyAverage: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val essentialPerYear: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val nonEssentialPerYear: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalPerYear: BigDecimal,
    val essentialShare: Double,
    val essentialCategories: List<CostOfLivingCategoryRow>,
    val nonEssentialCategories: List<CostOfLivingCategoryRow>,
)

data class ExplorerMonth(
    val year: Int,
    val month: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
)

data class ExplorerMonthLabel(
    val year: Int,
    val month: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
)

data class ExplorerDescriptionRow(
    val description: String,
    val occurrences: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalAmount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val averagePerOccurrence: BigDecimal,
)

data class ExplorerResponse(
    val scopeType: String, // "group" or "category"
    val scopeCode: String,
    val months: List<ExplorerMonth>,
    val priorMonths: List<ExplorerMonth>?,
    val priorYearsAvailable: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val averagePerMonth: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val medianPerMonth: BigDecimal,
    val highestMonth: ExplorerMonthLabel?,
    val lowestNonZeroMonth: ExplorerMonthLabel?,
    val topDescriptions: List<ExplorerDescriptionRow>?,
)

data class DailyPoint(
    val date: java.time.LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
)

data class DailyResponse(
    val from: java.time.LocalDate,
    val to: java.time.LocalDate,
    val direction: String,
    val days: List<DailyPoint>,
)
