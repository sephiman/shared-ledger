package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.common.errors.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

class AnalyticsRangeTest {

    @Test
    fun `parse returns null when the caller sends neither bound`() {
        assertThat(AnalyticsRange.parse(null, null)).isNull()
    }

    @Test
    fun `parse accepts a well-formed pair`() {
        val window = AnalyticsRange.parse("2025-03-14", "2026-02-09")
        assertThat(window).isEqualTo(DateWindow(LocalDate.of(2025, 3, 14), LocalDate.of(2026, 2, 9)))
    }

    @Test
    fun `parse rejects a half-supplied pair, naming the missing bound`() {
        assertThatThrownBy { AnalyticsRange.parse("2025-03-14", null) }
            .isInstanceOfSatisfying(AppException::class.java) { assertThat(it.args.toList()).contains("to") }
        assertThatThrownBy { AnalyticsRange.parse(null, "2026-02-09") }
            .isInstanceOfSatisfying(AppException::class.java) { assertThat(it.args.toList()).contains("from") }
    }

    @Test
    fun `parse rejects an unparseable date, naming the offending bound`() {
        assertThatThrownBy { AnalyticsRange.parse("not-a-date", "2026-02-09") }
            .isInstanceOfSatisfying(AppException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_PARAMETER")
                assertThat(it.args.toList()).contains("from")
            }
        assertThatThrownBy { AnalyticsRange.parse("2025-03-14", "2026-13-09") }
            .isInstanceOfSatisfying(AppException::class.java) { assertThat(it.args.toList()).contains("to") }
    }

    @Test
    fun `a reversed range is rejected`() {
        assertThatThrownBy { AnalyticsRange.parse("2026-02-09", "2025-03-14") }
            .isInstanceOfSatisfying(AppException::class.java) {
                assertThat(it.code).isEqualTo("INVALID_PARAMETER")
                assertThat(it.args.toList()).contains("from")
            }
    }

    @Test
    fun `an equal from and to is a valid one-day range`() {
        assertThat(AnalyticsRange.parse("2025-03-14", "2025-03-14")).isNotNull
    }

    @Test
    fun `a range wider than twenty years is rejected`() {
        assertThatThrownBy { AnalyticsRange.parse("2000-01-01", "2026-01-01") }
            .isInstanceOfSatisfying(AppException::class.java) { assertThat(it.args.toList()).contains("range_too_wide") }
    }

    @Test
    fun `toMonthWindow widens to the whole months the range touches`() {
        val window = AnalyticsRange.of(LocalDate.of(2025, 3, 14), LocalDate.of(2026, 2, 9)).toMonthWindow()
        assertThat(window.start).isEqualTo(YearMonth.of(2025, 3))
        assertThat(window.end).isEqualTo(YearMonth.of(2026, 2))
        assertThat(window.months).isEqualTo(12)
    }

    @Test
    fun `a window inside one month is one month long`() {
        val window = AnalyticsRange.of(LocalDate.of(2025, 3, 2), LocalDate.of(2025, 3, 28)).toMonthWindow()
        assertThat(window.months).isEqualTo(1)
        assertThat(window.each()).containsExactly(YearMonth.of(2025, 3))
    }

    @Test
    fun `each walks every month of the window in order, both ends included`() {
        val window = MonthWindow(YearMonth.of(2025, 11), YearMonth.of(2026, 2))
        assertThat(window.each()).containsExactly(
            YearMonth.of(2025, 11),
            YearMonth.of(2025, 12),
            YearMonth.of(2026, 1),
            YearMonth.of(2026, 2),
        )
    }
}
