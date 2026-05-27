package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RecurringDateMathTest {

    private fun template(
        cadence: Cadence,
        dayOfMonth: Short? = null,
        dayOfWeek: Short? = null,
        monthOfYear: Short? = null,
        dayOfMonthYearly: Short? = null,
        startDate: LocalDate = LocalDate.of(2025, 1, 1),
        endDate: LocalDate? = null,
    ) = RecurringTemplate(
        householdId = UUID.randomUUID(),
        direction = Direction.expense,
        categoryCode = "home.rent",
        amount = BigDecimal("100.00"),
        cadence = cadence,
        dayOfMonth = dayOfMonth,
        dayOfWeek = dayOfWeek,
        monthOfYear = monthOfYear,
        dayOfMonthYearly = dayOfMonthYearly,
        startDate = startDate,
        endDate = endDate,
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )

    @Test
    fun `monthly clamps day to end of month`() {
        val t = template(cadence = Cadence.monthly, dayOfMonth = 31, startDate = LocalDate.of(2025, 1, 1))
        val dates = RecurringDateMath.occurrencesInRange(t, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30))
        assertThat(dates).containsExactly(
            LocalDate.of(2025, 1, 31),
            LocalDate.of(2025, 2, 28),
            LocalDate.of(2025, 3, 31),
            LocalDate.of(2025, 4, 30),
        )
    }

    @Test
    fun `monthly clamps day in leap year February`() {
        val t = template(cadence = Cadence.monthly, dayOfMonth = 31, startDate = LocalDate.of(2024, 2, 1))
        val dates = RecurringDateMath.occurrencesInRange(t, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29))
        assertThat(dates).containsExactly(LocalDate.of(2024, 2, 29))
    }

    @Test
    fun `weekly fires every week on target day-of-week`() {
        // dayOfWeek 3 = Wednesday
        val t = template(cadence = Cadence.weekly, dayOfWeek = 3, startDate = LocalDate.of(2025, 1, 1))
        val dates = RecurringDateMath.occurrencesInRange(t, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31))
        assertThat(dates).containsExactly(
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 8),
            LocalDate.of(2025, 1, 15),
            LocalDate.of(2025, 1, 22),
            LocalDate.of(2025, 1, 29),
        )
    }

    @Test
    fun `yearly fires once per year on the given month-day`() {
        val t = template(cadence = Cadence.yearly, monthOfYear = 6, dayOfMonthYearly = 15, startDate = LocalDate.of(2023, 6, 15))
        val dates = RecurringDateMath.occurrencesInRange(t, LocalDate.of(2023, 1, 1), LocalDate.of(2026, 12, 31))
        assertThat(dates).containsExactly(
            LocalDate.of(2023, 6, 15),
            LocalDate.of(2024, 6, 15),
            LocalDate.of(2025, 6, 15),
            LocalDate.of(2026, 6, 15),
        )
    }

    @Test
    fun `respects end date`() {
        val t = template(cadence = Cadence.monthly, dayOfMonth = 1, startDate = LocalDate.of(2025, 1, 1), endDate = LocalDate.of(2025, 3, 15))
        val dates = RecurringDateMath.occurrencesInRange(t, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 1))
        assertThat(dates).containsExactly(
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 2, 1),
            LocalDate.of(2025, 3, 1),
        )
    }
}
