package com.sephilabs.sharedledger.analytics

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
import java.util.TreeMap
import java.util.UUID

/** Income and expense categories are estimated independently, so a category is keyed with its direction. */
data class FlowKey(val direction: Direction, val categoryCode: String)

/**
 * The Forecast method as of a target month. A category's expected month is the template amount of every
 * occurrence its active recurring templates have that month, plus the moving average of its non-template
 * (discretionary) amounts over the `window` complete months before the target. The average divides by the
 * months the household has data for, never padding a short history with zero months (Cost of living's rule),
 * and a net-negative average (refunds outweighing spending) expects nothing rather than a negative amount.
 */
class ForecastBasis(
    val window: MonthWindow,
    val monthsAvailable: Int,
    val monthlyHistory: Map<FlowKey, Map<YearMonth, BigDecimal>>,
    val discretionaryAverage: Map<FlowKey, BigDecimal>,
    private val templates: List<RecurringTemplate>,
) {
    /** The months the average actually covers: the most recent `monthsAvailable` of the window. */
    val basisWindow: MonthWindow?
        get() = if (monthsAvailable == 0) null
        else MonthWindow(window.end.minusMonths((monthsAvailable - 1).toLong()), window.end)

    fun expectedDiscretionary(key: FlowKey): BigDecimal =
        (discretionaryAverage[key] ?: BigDecimal.ZERO).max(BigDecimal.ZERO)

    fun recurringScheduled(month: YearMonth): Map<FlowKey, BigDecimal> =
        recurringFrom(month) { month.atDay(1) }

    /** Occurrences the materializer has not produced yet, so they are not among the month's actuals. */
    fun recurringStillDue(month: YearMonth): Map<FlowKey, BigDecimal> =
        recurringFrom(month) { maxOf(month.atDay(1), RecurringDateMath.firstUnmaterializedDate(it)) }

    private fun recurringFrom(month: YearMonth, start: (RecurringTemplate) -> LocalDate): Map<FlowKey, BigDecimal> {
        val totals = LinkedHashMap<FlowKey, BigDecimal>()
        for (template in templates) {
            val occurrences = RecurringDateMath.occurrencesInRange(template, start(template), month.atEndOfMonth()).size
            if (occurrences == 0) continue
            totals.merge(FlowKey(template.direction, template.categoryCode),
                template.amount.multiply(BigDecimal.valueOf(occurrences.toLong()))) { a, b -> a + b }
        }
        return totals
    }
}

@Component
class ForecastEstimator(
    private val transactions: TransactionRepository,
    private val recurring: RecurringTemplateRepository,
) {
    fun basis(householdId: UUID, target: YearMonth, windowMonths: Int): ForecastBasis {
        val window = MonthWindow(target.minusMonths(windowMonths.toLong()), target.minusMonths(1))
        val history = LinkedHashMap<FlowKey, TreeMap<YearMonth, BigDecimal>>()
        val discretionaryTotals = LinkedHashMap<FlowKey, BigDecimal>()
        for (r in transactions.aggregationRows(householdId, window.start.atDay(1), window.end.atEndOfMonth())) {
            val key = FlowKey(r.direction, r.categoryCode)
            history.getOrPut(key) { TreeMap() }.merge(YearMonth.from(r.occurrenceDate), r.amount) { a, b -> a + b }
            if (r.recurringTemplateId == null) discretionaryTotals.merge(key, r.amount) { a, b -> a + b }
        }
        val monthsAvailable = window.monthsCoveredSince(transactions.dateBounds(householdId).minDate)
        val denom = monthsAvailable.coerceAtLeast(1).toBigDecimal()
        return ForecastBasis(
            window = window,
            monthsAvailable = monthsAvailable,
            monthlyHistory = history,
            discretionaryAverage = discretionaryTotals.mapValues { it.value.divide(denom, 2, RoundingMode.HALF_EVEN) },
            templates = recurring.findAllByHouseholdIdAndActiveTrue(householdId),
        )
    }
}
