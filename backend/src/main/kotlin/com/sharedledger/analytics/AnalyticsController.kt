package com.sharedledger.analytics

import com.sharedledger.common.errors.AppException
import com.sharedledger.observability.AppMetrics
import com.sharedledger.transaction.Direction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
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
}
