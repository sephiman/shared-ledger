package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class DiscretionaryTermTest {

    private val october = YearMonth.of(2025, 10)
    private val groceries = FlowKey(Direction.expense, "groceries.groceries")
    private val restaurants = FlowKey(Direction.expense, "outings.restaurants")
    private val refunded = FlowKey(Direction.expense, "shopping.clothing")
    private val basis = ForecastBasis(
        window = MonthWindow(YearMonth.of(2025, 7), YearMonth.of(2025, 9)),
        monthsAvailable = 3,
        monthlyHistory = emptyMap(),
        discretionaryAverage = mapOf(
            groceries to BigDecimal("310.00"),
            restaurants to BigDecimal("100.00"),
            refunded to BigDecimal("-20.00"),
        ),
        templates = emptyList(),
    )

    @Test
    fun `the current day counts as elapsed`() {
        assertThat(MonthProgress.asOf(october.atDay(18))).isEqualTo(MonthProgress(daysInMonth = 31, daysRemaining = 13))
        assertThat(MonthProgress.asOf(LocalDate.of(2024, 2, 29))).isEqualTo(MonthProgress(daysInMonth = 29, daysRemaining = 0))
        assertThat(MonthProgress.wholeMonth(YearMonth.of(2026, 2))).isEqualTo(MonthProgress(daysInMonth = 28, daysRemaining = 28))
    }

    @Test
    fun `on day 1 prorate leaves out the first day while envelope expects the whole average`() {
        val dayOne = MonthProgress.asOf(october.atDay(1))

        assertThat(term(ProjectionMode.PRORATE, BigDecimal.ZERO, dayOne)).isEqualByComparingTo("300.00")
        assertThat(term(ProjectionMode.ENVELOPE, BigDecimal.ZERO, dayOne)).isEqualByComparingTo("310.00")
    }

    @Test
    fun `mid month prorate is the share of the days ahead whatever was spent`() {
        val eighteenth = MonthProgress.asOf(october.atDay(18))

        assertThat(term(ProjectionMode.PRORATE, BigDecimal("200.00"), eighteenth)).isEqualByComparingTo("130.00")
        assertThat(term(ProjectionMode.PRORATE, BigDecimal("900.00"), eighteenth)).isEqualByComparingTo("130.00")
        assertThat(term(ProjectionMode.ENVELOPE, BigDecimal("200.00"), eighteenth)).isEqualByComparingTo("110.00")
        assertThat(term(ProjectionMode.ENVELOPE, BigDecimal("900.00"), eighteenth)).isEqualByComparingTo("0.00")
    }

    @Test
    fun `on the last day prorate expects nothing more`() {
        val lastDay = MonthProgress.asOf(october.atEndOfMonth())

        assertThat(term(ProjectionMode.PRORATE, BigDecimal.ZERO, lastDay)).isEqualByComparingTo("0.00")
        assertThat(term(ProjectionMode.ENVELOPE, BigDecimal("100.00"), lastDay)).isEqualByComparingTo("210.00")
    }

    @Test
    fun `prorate rounds to the cent half even`() {
        // 100.00 × 5/31 = 16.129…
        val term = basis.discretionaryTerm(restaurants, ProjectionMode.PRORATE, BigDecimal.ZERO, MonthProgress(31, 5))

        assertThat(term).isEqualTo(BigDecimal("16.13"))
    }

    @Test
    fun `a category with no history expects nothing in either mode`() {
        val unknown = FlowKey(Direction.expense, "health.medical")
        val eighteenth = MonthProgress.asOf(october.atDay(18))

        for (mode in ProjectionMode.entries) {
            assertThat(basis.discretionaryTerm(unknown, mode, BigDecimal.ZERO, eighteenth)).isEqualByComparingTo("0.00")
        }
    }

    @Test
    fun `a net negative average expects nothing in either mode`() {
        val dayOne = MonthProgress.asOf(october.atDay(1))

        for (mode in ProjectionMode.entries) {
            assertThat(basis.discretionaryTerm(refunded, mode, BigDecimal.ZERO, dayOne)).isEqualByComparingTo("0.00")
        }
    }

    @Test
    fun `both modes expect exactly the average over a whole future month`() {
        for (key in listOf(groceries, restaurants, refunded)) {
            for (month in listOf(october, YearMonth.of(2026, 2), YearMonth.of(2024, 2), YearMonth.of(2026, 4))) {
                val whole = MonthProgress.wholeMonth(month)
                val envelope = basis.discretionaryTerm(key, ProjectionMode.ENVELOPE, BigDecimal.ZERO, whole)
                val prorate = basis.discretionaryTerm(key, ProjectionMode.PRORATE, BigDecimal.ZERO, whole)

                assertThat(prorate).isEqualByComparingTo(envelope)
                assertThat(prorate).isEqualByComparingTo(basis.expectedDiscretionary(key))
            }
        }
    }

    private fun term(mode: ProjectionMode, spent: BigDecimal, progress: MonthProgress): BigDecimal =
        basis.discretionaryTerm(groceries, mode, spent, progress)
}
