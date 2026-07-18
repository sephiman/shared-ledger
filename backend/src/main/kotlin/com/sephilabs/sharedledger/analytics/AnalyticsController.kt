package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/analytics")
class AnalyticsController(
    private val service: AnalyticsService,
    private val metrics: AppMetrics,
) {

    @GetMapping("/month")
    fun month(
        @PathVariable householdId: UUID,
        @RequestParam year: Int,
        @RequestParam month: Int,
    ): MonthDashboardResponse = metrics.analyticsTimer("month").record<MonthDashboardResponse> {
        service.monthDashboard(householdId, year, month)
    }!!

    @GetMapping("/year")
    fun year(
        @PathVariable householdId: UUID,
        @RequestParam year: Int,
    ): YearDashboardResponse = metrics.analyticsTimer("year").record<YearDashboardResponse> {
        service.yearDashboard(householdId, year)
    }!!

    @GetMapping("/year-over-year")
    fun yoy(
        @PathVariable householdId: UUID,
        @RequestParam month: Int,
        @RequestParam(defaultValue = "5") years: Int,
    ): YearOverYearResponse = metrics.analyticsTimer("year_over_year").record<YearOverYearResponse> {
        service.yearOverYear(householdId, month, years)
    }!!

    @GetMapping("/year-by-year")
    fun yearByYear(
        @PathVariable householdId: UUID,
        @RequestParam years: List<Int>,
    ): YearByYearResponse = metrics.analyticsTimer("year_by_year").record<YearByYearResponse> {
        service.yearByYear(householdId, years)
    }!!

    @GetMapping("/trailing-12")
    fun trailing(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) asOf: String?,
    ): TrailingResponse {
        val ym = asOf?.let { YearMonth.parse(it) } ?: YearMonth.now()
        return service.trailing12(householdId, ym)
    }

    @GetMapping("/forecast")
    fun forecast(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "6") horizonMonths: Int,
        @RequestParam(defaultValue = "3") windowMonths: Int,
    ): ForecastResponse = metrics.analyticsTimer("forecast").record<ForecastResponse> {
        service.forecast(householdId, horizonMonths.coerceIn(1, 12), windowMonths.coerceIn(1, 24))
    }!!

    @GetMapping("/dashboard-extras")
    fun dashboardExtras(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) asOf: String?,
    ): DashboardExtrasResponse {
        val ym = asOf?.let { YearMonth.parse(it) } ?: YearMonth.now()
        return metrics.analyticsTimer("dashboard_extras").record<DashboardExtrasResponse> {
            service.dashboardExtras(householdId, ym)
        }!!
    }

    @GetMapping("/allocation")
    fun allocation(
        @PathVariable householdId: UUID,
        @RequestParam year: Int,
        @RequestParam(required = false) month: Int?,
    ): AllocationResponse = metrics.analyticsTimer("allocation").record<AllocationResponse> {
        if (month != null && month !in 1..12) throw AppException.badRequest("INVALID_PARAMETER", "month")
        service.allocation(householdId, year, month)
    }!!

    @GetMapping("/money-flow")
    fun moneyFlow(
        @PathVariable householdId: UUID,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(defaultValue = "group") level: String,
    ): MoneyFlowResponse = metrics.analyticsTimer("money_flow").record<MoneyFlowResponse> {
        if (level !in setOf("group", "category")) {
            throw AppException.badRequest("INVALID_PARAMETER", "level")
        }
        val pFrom = runCatching { LocalDate.parse(from) }
            .getOrElse { throw AppException.badRequest("INVALID_PARAMETER", "from") }
        val pTo = runCatching { LocalDate.parse(to) }
            .getOrElse { throw AppException.badRequest("INVALID_PARAMETER", "to") }
        if (pFrom.isAfter(pTo)) throw AppException.badRequest("INVALID_PARAMETER", "from")
        if (ChronoUnit.DAYS.between(pFrom, pTo) > 366L * 20L) {
            throw AppException.badRequest("INVALID_PARAMETER", "range_too_wide")
        }
        service.moneyFlow(householdId, pFrom, pTo, level)
    }!!

    @GetMapping("/top-movers")
    fun topMovers(
        @PathVariable householdId: UUID,
        @RequestParam year: Int,
        @RequestParam month: Int,
        @RequestParam(defaultValue = "year_ago") baseline: String,
    ): TopMoversResponse = metrics.analyticsTimer("top_movers").record<TopMoversResponse> {
        if (month !in 1..12) throw AppException.badRequest("INVALID_PARAMETER", "month")
        if (baseline != "year_ago" && baseline != "trailing6_avg") {
            throw AppException.badRequest("INVALID_PARAMETER", "baseline")
        }
        service.topMovers(householdId, year, month, baseline)
    }!!

    @GetMapping("/recurring-share")
    fun recurringShare(
        @PathVariable householdId: UUID,
        @RequestParam scope: String,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) month: Int?,
    ): RecurringShareResponse = metrics.analyticsTimer("recurring_share").record<RecurringShareResponse> {
        if (scope !in setOf("month", "trailing12", "ytd", "year")) {
            throw AppException.badRequest("INVALID_PARAMETER", "scope")
        }
        if (month != null && month !in 1..12) throw AppException.badRequest("INVALID_PARAMETER", "month")
        service.recurringShare(householdId, scope, year, month)
    }!!

    @GetMapping("/heatmap")
    fun heatmap(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "24") months: Int,
        @RequestParam(defaultValue = "expense") direction: String,
    ): HeatmapResponse = metrics.analyticsTimer("heatmap").record<HeatmapResponse> {
        val dir = when (direction) {
            "expense" -> Direction.expense
            "income" -> Direction.income
            else -> throw AppException.badRequest("INVALID_PARAMETER", "direction")
        }
        val safeMonths = when {
            months <= 0 -> 24
            months > 600 -> 9999 // "full history"
            else -> months
        }
        service.heatmap(householdId, safeMonths, dir)
    }!!

    @GetMapping("/daily")
    fun daily(
        @PathVariable householdId: UUID,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(defaultValue = "expense") direction: String,
    ): DailyResponse = metrics.analyticsTimer("daily").record<DailyResponse> {
        val dir = when (direction) {
            "expense" -> Direction.expense
            "income" -> Direction.income
            else -> throw AppException.badRequest("INVALID_PARAMETER", "direction")
        }
        val pFrom = runCatching { LocalDate.parse(from) }
            .getOrElse { throw AppException.badRequest("INVALID_PARAMETER", "from") }
        val pTo = runCatching { LocalDate.parse(to) }
            .getOrElse { throw AppException.badRequest("INVALID_PARAMETER", "to") }
        if (pFrom.isAfter(pTo)) throw AppException.badRequest("INVALID_PARAMETER", "from")
        if (ChronoUnit.DAYS.between(pFrom, pTo) > 366L * 20L) {
            throw AppException.badRequest("INVALID_PARAMETER", "range_too_wide")
        }
        service.daily(householdId, pFrom, pTo, dir)
    }!!

    @GetMapping("/contribution-series")
    fun contributionSeries(
        @PathVariable householdId: UUID,
    ): ContributionSeriesResponse = metrics.analyticsTimer("contribution_series").record<ContributionSeriesResponse> {
        service.contributionSeries(householdId)
    }!!

    @GetMapping("/years-available")
    fun yearsAvailable(
        @PathVariable householdId: UUID,
    ): YearsAvailableResponse = service.yearsAvailable(householdId)

    @GetMapping("/cost-of-living")
    fun costOfLiving(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) asOf: String?,
    ): CostOfLivingResponse {
        val ym = asOf?.let { YearMonth.parse(it) } ?: YearMonth.now()
        return metrics.analyticsTimer("cost_of_living").record<CostOfLivingResponse> {
            service.costOfLiving(householdId, ym)
        }!!
    }

    @GetMapping("/explorer")
    fun explorer(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) scopeType: String?,
        @RequestParam(required = false) scopeCode: String?,
        @RequestParam(defaultValue = "12") months: Int,
        @RequestParam(defaultValue = "false") yoyOverlay: Boolean,
    ): ExplorerResponse = metrics.analyticsTimer("explorer").record<ExplorerResponse> {
        if (scopeType != null && scopeType !in setOf("group", "category")) {
            throw AppException.badRequest("INVALID_PARAMETER", "scopeType")
        }
        if ((scopeType == null) != (scopeCode == null)) {
            throw AppException.badRequest("INVALID_PARAMETER", "scope")
        }
        val safeMonths = when {
            months <= 0 -> 12
            months > 600 -> 9999 // "full history"
            else -> months
        }
        service.explorer(householdId, scopeType, scopeCode, safeMonths, yoyOverlay)
    }!!
}
