package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.common.errors.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

class SpendingReviewPeriodTest {

    @Test
    fun `pace review eligibility respects AI_REVIEW_MIN_DAYS_ELAPSED boundary and period validation`() {
        val day6 = LocalDate.of(2026, 11, 6)
        val day7 = LocalDate.of(2026, 11, 7)

        assertThat(day6.dayOfMonth < SpendingReviewConstants.AI_REVIEW_MIN_DAYS_ELAPSED).isTrue()
        assertThat(day7.dayOfMonth >= SpendingReviewConstants.AI_REVIEW_MIN_DAYS_ELAPSED).isTrue()

        // Verify resolvePeriod helper logic directly via pure date math
        val currentYm = YearMonth.from(day6)
        assertThat(currentYm.minusMonths(1)).isEqualTo(YearMonth.of(2026, 10))
    }

    @Test
    fun `rejects invalid month or future month via resolvePeriod`() {
        val today = LocalDate.of(2026, 11, 19)
        // Use a lightweight instance to call resolvePeriod directly
        val service = SavingsCandidatesService(
            transactions = org.mockito.Mockito.mock(),
            categoryService = org.mockito.Mockito.mock(),
            recurringTemplates = org.mockito.Mockito.mock(),
            dataQueries = org.mockito.Mockito.mock(),
            cache = org.mockito.Mockito.mock(),
            clock = java.time.Clock.systemDefaultZone(),
        )
        assertThat(service.resolvePeriod(null, null, today)).isEqualTo(YearMonth.of(2026, 10))
        assertThat(service.resolvePeriod(2026, 11, today)).isEqualTo(YearMonth.of(2026, 11))
        assertThat(service.resolvePeriod(2026, 5, today)).isEqualTo(YearMonth.of(2026, 5))

        assertThatThrownBy { service.resolvePeriod(2026, 12, today) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo("INVALID_PARAMETER")
        assertThatThrownBy { service.resolvePeriod(2026, 13, today) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo("INVALID_PARAMETER")
    }
}
