package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.common.errors.AppException
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Longest window any analytics endpoint will aggregate over, in days. */
private const val MAX_RANGE_DAYS: Long = 366L * 20L

/** An explicit, validated from/to window supplied by the caller. */
data class DateWindow(val from: LocalDate, val to: LocalDate)

/** The same window seen as whole months, for the month-bucketed endpoints. */
data class MonthWindow(val start: YearMonth, val end: YearMonth) {
    val months: Int
        get() = ((end.year - start.year) * 12 + (end.monthValue - start.monthValue)) + 1

    /** Every month in the window, ascending, both ends included. */
    fun each(): List<YearMonth> {
        val out = mutableListOf<YearMonth>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            out += cursor
            cursor = cursor.plusMonths(1)
        }
        return out
    }
}

/** Widen a window to the whole months it touches, matching how the range selector snaps in the UI. */
fun DateWindow.toMonthWindow(): MonthWindow = MonthWindow(YearMonth.from(from), YearMonth.from(to))

/**
 * One from/to convention for every analytics endpoint: both bounds or neither, parseable, correctly
 * ordered, and no wider than 20 years. Endpoints that also take a month-count parameter treat an absent
 * window as "fall back to the count", which is what keeps older clients working.
 */
object AnalyticsRange {

    /** Parse an optional from/to pair off a request. Null means the caller supplied neither. */
    fun parse(from: String?, to: String?): DateWindow? {
        if (from == null && to == null) return null
        if (from == null) throw AppException.badRequest("INVALID_PARAMETER", "from")
        if (to == null) throw AppException.badRequest("INVALID_PARAMETER", "to")
        return of(parseDate(from, "from"), parseDate(to, "to"))
    }

    /** Validate an already-parsed pair — used by the endpoints where from/to are required. */
    fun of(from: LocalDate, to: LocalDate): DateWindow {
        if (from.isAfter(to)) throw AppException.badRequest("INVALID_PARAMETER", "from")
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw AppException.badRequest("INVALID_PARAMETER", "range_too_wide")
        }
        return DateWindow(from, to)
    }

    fun parseDate(value: String, field: String): LocalDate =
        runCatching { LocalDate.parse(value) }
            .getOrElse { throw AppException.badRequest("INVALID_PARAMETER", field) }
}
