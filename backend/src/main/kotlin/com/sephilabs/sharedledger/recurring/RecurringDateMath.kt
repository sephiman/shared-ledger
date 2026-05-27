package com.sephilabs.sharedledger.recurring

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

object RecurringDateMath {

    fun occurrencesInRange(template: RecurringTemplate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val effectiveTo = template.endDate?.let { if (it.isBefore(to)) it else to } ?: to
        if (from.isAfter(effectiveTo)) return emptyList()
        return when (template.cadence) {
            Cadence.weekly -> weeklyOccurrences(template, from, effectiveTo)
            Cadence.monthly -> monthlyOccurrences(template, from, effectiveTo)
            Cadence.yearly -> yearlyOccurrences(template, from, effectiveTo)
        }
    }

    fun nextOccurrenceAfter(template: RecurringTemplate, referenceDate: LocalDate): LocalDate? {
        val ceiling = template.endDate ?: referenceDate.plusYears(5)
        val occurrences = occurrencesInRange(template, referenceDate.plusDays(1), ceiling)
        return occurrences.firstOrNull()
    }

    private fun weeklyOccurrences(template: RecurringTemplate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val targetDow = DayOfWeek.of(template.dayOfWeek?.toInt() ?: return emptyList())
        val first = (template.startDate.takeIf { !it.isAfter(from) } ?: template.startDate).let { start ->
            var d = if (start.isBefore(from)) from else start
            while (d.dayOfWeek != targetDow) d = d.plusDays(1)
            d
        }
        val result = mutableListOf<LocalDate>()
        var d = first
        while (!d.isAfter(to)) {
            if (!d.isBefore(template.startDate)) result += d
            d = d.plusWeeks(1)
        }
        return result
    }

    private fun monthlyOccurrences(template: RecurringTemplate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val day = template.dayOfMonth?.toInt() ?: return emptyList()
        val start = template.startDate
        var cursor = YearMonth.from(maxOf(from, start))
        val endCursor = YearMonth.from(to)
        val result = mutableListOf<LocalDate>()
        while (!cursor.isAfter(endCursor)) {
            val safeDay = minOf(day, cursor.lengthOfMonth())
            val candidate = cursor.atDay(safeDay)
            if (!candidate.isBefore(start) && !candidate.isBefore(from) && !candidate.isAfter(to)) {
                result += candidate
            }
            cursor = cursor.plusMonths(1)
        }
        return result
    }

    private fun yearlyOccurrences(template: RecurringTemplate, from: LocalDate, to: LocalDate): List<LocalDate> {
        val month = template.monthOfYear?.toInt() ?: return emptyList()
        val day = template.dayOfMonthYearly?.toInt() ?: return emptyList()
        val result = mutableListOf<LocalDate>()
        var year = maxOf(from.year, template.startDate.year)
        while (year <= to.year) {
            val ym = YearMonth.of(year, month)
            val safeDay = minOf(day, ym.lengthOfMonth())
            val candidate = ym.atDay(safeDay)
            if (!candidate.isBefore(template.startDate) && !candidate.isBefore(from) && !candidate.isAfter(to)) {
                result += candidate
            }
            year++
        }
        return result
    }
}
