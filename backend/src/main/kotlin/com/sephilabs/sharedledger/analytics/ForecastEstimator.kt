package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import com.sephilabs.sharedledger.analytics.recurring.DetectedCadence
import com.sephilabs.sharedledger.analytics.recurring.DetectedRecurringCache
import com.sephilabs.sharedledger.analytics.recurring.DetectedRecurringCharge
import com.sephilabs.sharedledger.analytics.recurring.DetectedRecurringSnapshot
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.recurring.RecurringDateMath
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.UUID

/** How much of a month is still ahead. The current day counts as elapsed, like the panel's "Day N of M". */
data class MonthProgress(val daysInMonth: Int, val daysRemaining: Int) {
    companion object {
        fun asOf(today: LocalDate): MonthProgress {
            val daysInMonth = YearMonth.from(today).lengthOfMonth()
            return MonthProgress(daysInMonth, daysInMonth - today.dayOfMonth)
        }

        fun wholeMonth(month: YearMonth): MonthProgress = MonthProgress(month.lengthOfMonth(), month.lengthOfMonth())
    }
}

/** Income and expense categories are estimated independently, so a category is keyed with its direction. */
data class FlowKey(val direction: Direction, val categoryCode: String)

/**
 * The Forecast method as of a target month. A category's expected month is the scheduled amount of every
 * active recurring template plus every detected recurring expense charge for that month, plus the moving
 * average of its remaining discretionary amounts over the `window` complete months before the target.
 * No transaction is ever counted in both the recurring and discretionary terms.
 */
class ForecastBasis(
    val window: MonthWindow,
    val monthsAvailable: Int,
    val monthlyHistory: Map<FlowKey, Map<YearMonth, BigDecimal>>,
    val discretionaryAverage: Map<FlowKey, BigDecimal>,
    private val templates: List<RecurringTemplate>,
    val detectedSnapshot: DetectedRecurringSnapshot = DetectedRecurringSnapshot.EMPTY,
) {
    val detectedMatchedTxIds: Set<UUID> get() = detectedSnapshot.detectedMatchedTxIds

    /** The months the average actually covers: the most recent `monthsAvailable` of the window. */
    val basisWindow: MonthWindow?
        get() = if (monthsAvailable == 0) null
        else MonthWindow(window.end.minusMonths((monthsAvailable - 1).toLong()), window.end)

    fun expectedDiscretionary(key: FlowKey): BigDecimal =
        (discretionaryAverage[key] ?: BigDecimal.ZERO).max(BigDecimal.ZERO)

    /** The one place a projection mode is applied. Over a whole month both modes expect exactly the average:
     *  nothing is spent yet, and the prorated share is M/M of it. */
    fun discretionaryTerm(key: FlowKey, mode: ProjectionMode, spentSoFar: BigDecimal, progress: MonthProgress): BigDecimal =
        when (mode) {
            ProjectionMode.ENVELOPE -> (expectedDiscretionary(key) - spentSoFar).max(BigDecimal.ZERO)
            ProjectionMode.PRORATE -> expectedDiscretionary(key)
                .multiply(BigDecimal.valueOf(progress.daysRemaining.toLong()))
                .divide(BigDecimal.valueOf(progress.daysInMonth.toLong()), Money.SCALE, RoundingMode.HALF_EVEN)
        }

    fun recurringScheduled(month: YearMonth): Map<FlowKey, BigDecimal> {
        val totals = recurringTemplatesFrom(month) { month.atDay(1) }
        for (charge in detectedSnapshot.detectedOnly) {
            val amount = detectedScheduledForMonth(charge, month)
            if (amount.signum() > 0) {
                totals.merge(FlowKey(Direction.expense, charge.categoryCode), amount) { a, b -> a + b }
            }
        }
        return totals
    }

    /** Occurrences not yet materialized/observed this month, so they are not among the month's actuals. */
    fun recurringStillDue(month: YearMonth, asOf: LocalDate = month.atEndOfMonth()): Map<FlowKey, BigDecimal> {
        val totals = recurringTemplatesFrom(month) { maxOf(month.atDay(1), RecurringDateMath.firstUnmaterializedDate(it)) }
        for (charge in detectedSnapshot.detectedOnly) {
            val amount = detectedStillDueInMonth(charge, month, asOf)
            if (amount.signum() > 0) {
                totals.merge(FlowKey(Direction.expense, charge.categoryCode), amount) { a, b -> a + b }
            }
        }
        return totals
    }

    private fun recurringTemplatesFrom(month: YearMonth, start: (RecurringTemplate) -> LocalDate): MutableMap<FlowKey, BigDecimal> {
        val totals = LinkedHashMap<FlowKey, BigDecimal>()
        for (template in templates) {
            val occurrences = RecurringDateMath.occurrencesInRange(template, start(template), month.atEndOfMonth()).size
            if (occurrences == 0) continue
            totals.merge(
                FlowKey(template.direction, template.categoryCode),
                template.amount.multiply(BigDecimal.valueOf(occurrences.toLong())),
            ) { a, b -> a + b }
        }
        return totals
    }

    private fun detectedScheduledForMonth(charge: DetectedRecurringCharge, month: YearMonth): BigDecimal {
        val lastDate = charge.lastOccurrenceDate ?: return BigDecimal.ZERO
        return when (charge.cadence) {
            DetectedCadence.monthly ->
                if (month.monthValue in charge.skippedCalendarMonths) BigDecimal.ZERO else charge.latestAmount
            DetectedCadence.weekly -> {
                val count = countWeeklyOccurrencesInRange(lastDate, month.atDay(1), month.atEndOfMonth())
                charge.latestAmount.multiply(BigDecimal.valueOf(count.toLong()))
            }
            DetectedCadence.quarterly -> {
                val monthsBetween = ChronoUnit.MONTHS.between(YearMonth.from(lastDate), month)
                if (monthsBetween > 0 && monthsBetween % 3L == 0L) charge.latestAmount else BigDecimal.ZERO
            }
            DetectedCadence.yearly ->
                if (lastDate.monthValue == month.monthValue) charge.latestAmount else BigDecimal.ZERO
        }
    }

    private fun detectedStillDueInMonth(charge: DetectedRecurringCharge, month: YearMonth, asOf: LocalDate): BigDecimal {
        val lastDate = charge.lastOccurrenceDate ?: return BigDecimal.ZERO
        val matchedCount = detectedSnapshot.matchedCountInMonth(charge, month)
        val matchedAmount = detectedSnapshot.matchedAmountInMonth(charge, month)
        return when (charge.cadence) {
            DetectedCadence.monthly -> {
                if (month.monthValue in charge.skippedCalendarMonths) return BigDecimal.ZERO
                if (charge.multiPerMonth) {
                    (charge.latestAmount - matchedAmount).max(BigDecimal.ZERO)
                } else {
                    if (matchedCount > 0) BigDecimal.ZERO else charge.latestAmount
                }
            }
            DetectedCadence.weekly -> {
                val startAfter = maxOf(asOf, lastDate).plusDays(1)
                if (startAfter.isAfter(month.atEndOfMonth())) return BigDecimal.ZERO
                val count = countWeeklyOccurrencesInRange(lastDate, startAfter, month.atEndOfMonth())
                charge.latestAmount.multiply(BigDecimal.valueOf(count.toLong()))
            }
            DetectedCadence.quarterly -> {
                if (matchedCount > 0) return BigDecimal.ZERO
                val monthsBetween = ChronoUnit.MONTHS.between(YearMonth.from(lastDate), month)
                if (monthsBetween > 0 && monthsBetween % 3L == 0L) charge.latestAmount else BigDecimal.ZERO
            }
            DetectedCadence.yearly -> {
                if (matchedCount > 0) return BigDecimal.ZERO
                if (lastDate.monthValue == month.monthValue && lastDate.year < month.year) charge.latestAmount else BigDecimal.ZERO
            }
        }
    }

    private fun countWeeklyOccurrencesInRange(anchorDate: LocalDate, from: LocalDate, to: LocalDate): Int {
        if (from.isAfter(to)) return 0
        var cursor = anchorDate.plusDays(7)
        if (cursor.isBefore(from)) {
            val daysGap = ChronoUnit.DAYS.between(cursor, from)
            val weeks = (daysGap + 6L) / 7L
            cursor = cursor.plusDays(weeks * 7L)
        }
        var count = 0
        while (!cursor.isAfter(to)) {
            count++
            cursor = cursor.plusDays(7)
        }
        return count
    }
}

@Component
class ForecastEstimator(
    private val transactions: TransactionRepository,
    private val recurring: RecurringTemplateRepository,
    private val categoryService: CategoryService,
    private val detectedRecurringCache: DetectedRecurringCache,
) {
    fun basis(
        householdId: UUID,
        target: YearMonth,
        windowMonths: Int,
        asOfDate: LocalDate = target.atEndOfMonth(),
    ): ForecastBasis {
        val window = MonthWindow(target.minusMonths(windowMonths.toLong()), target.minusMonths(1))
        val activeTemplates = recurring.findAllByHouseholdIdAndActiveTrue(householdId)
        val bounds = transactions.dateBounds(householdId)
        val catMeta = categoryService.listForHousehold(householdId).associate { it.code to (it.group to it.essential) }
        val detectedSnapshot = detectedRecurringCache.detectForProjection(
            householdId = householdId,
            asOfDate = asOfDate,
            twoYearWindowEnd = target.minusMonths(1),
            activeExpenseTemplates = activeTemplates.filter { it.direction == Direction.expense },
            categoryMeta = catMeta,
            householdMinDate = bounds.minDate,
        )
        val detectedTxIds = detectedSnapshot.detectedMatchedTxIds

        val history = LinkedHashMap<FlowKey, TreeMap<YearMonth, BigDecimal>>()
        val discretionaryTotals = LinkedHashMap<FlowKey, BigDecimal>()
        for (r in transactions.aggregationRows(householdId, window.start.atDay(1), window.end.atEndOfMonth())) {
            val key = FlowKey(r.direction, r.categoryCode)
            history.getOrPut(key) { TreeMap() }.merge(YearMonth.from(r.occurrenceDate), r.amount) { a, b -> a + b }
            if (r.recurringTemplateId == null && r.id !in detectedTxIds) {
                discretionaryTotals.merge(key, r.amount) { a, b -> a + b }
            }
        }
        val monthsAvailable = window.monthsCoveredSince(bounds.minDate)
        val denom = monthsAvailable.coerceAtLeast(1).toBigDecimal()
        return ForecastBasis(
            window = window,
            monthsAvailable = monthsAvailable,
            monthlyHistory = history,
            discretionaryAverage = discretionaryTotals.mapValues { it.value.divide(denom, 2, RoundingMode.HALF_EVEN) },
            templates = activeTemplates,
            detectedSnapshot = detectedSnapshot,
        )
    }
}
