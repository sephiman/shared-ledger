package com.sephilabs.sharedledger.lending

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

object LendingScheduleDateMath {

    fun occurrencesInRange(
        schedule: LendingSchedule,
        lendingStartDate: LocalDate,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        if (from.isAfter(to)) return emptyList()
        return when (schedule.frequency) {
            LendingFrequency.weekly -> weekly(schedule, lendingStartDate, from, to)
            LendingFrequency.monthly -> monthly(schedule, lendingStartDate, from, to)
            LendingFrequency.yearly -> yearly(schedule, lendingStartDate, from, to)
        }
    }

    private fun weekly(s: LendingSchedule, start: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val targetDow = DayOfWeek.of(s.dayOfWeek?.toInt() ?: return emptyList())
        var d = if (start.isAfter(from)) start else from
        while (d.dayOfWeek != targetDow) d = d.plusDays(1)
        val out = mutableListOf<LocalDate>()
        while (!d.isAfter(to)) {
            if (!d.isBefore(start)) out += d
            d = d.plusWeeks(1)
        }
        return out
    }

    private fun monthly(s: LendingSchedule, start: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val day = s.dayOfMonth?.toInt() ?: return emptyList()
        var cursor = YearMonth.from(maxOf(from, start))
        val end = YearMonth.from(to)
        val out = mutableListOf<LocalDate>()
        while (!cursor.isAfter(end)) {
            val candidate = cursor.atDay(minOf(day, cursor.lengthOfMonth()))
            if (!candidate.isBefore(start) && !candidate.isBefore(from) && !candidate.isAfter(to)) {
                out += candidate
            }
            cursor = cursor.plusMonths(1)
        }
        return out
    }

    private fun yearly(s: LendingSchedule, start: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val day = s.dayOfMonth?.toInt() ?: return emptyList()
        val anchorMonth = start.monthValue
        val anchor = MonthDay.of(anchorMonth, minOf(day, 28))
        val out = mutableListOf<LocalDate>()
        var year = maxOf(from.year, start.year)
        while (year <= to.year) {
            val ym = YearMonth.of(year, anchor.monthValue)
            val safeDay = minOf(day, ym.lengthOfMonth())
            val candidate = ym.atDay(safeDay)
            if (!candidate.isBefore(start) && !candidate.isBefore(from) && !candidate.isAfter(to)) {
                out += candidate
            }
            year++
        }
        return out
    }
}
