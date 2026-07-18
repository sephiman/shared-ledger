package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.portfolio.MoneyWeightedReturn.Failure
import com.sephilabs.sharedledger.portfolio.MoneyWeightedReturn.Flow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Fixtures are hand-verifiable: spans are picked so days / 365.25 is exact
 * (2022-01-01 → 2026-01-01 = 1461 days = 4.0 years, 2024 being a leap year),
 * and amounts are powers of the expected rate (1000 × 1.1⁴ = 1464.10).
 */
class MoneyWeightedReturnTest {

    private fun flow(date: String, amount: String) = Flow(LocalDate.parse(date), BigDecimal(amount))

    @Test
    fun `a single buy growing 10 percent a year for four years annualizes to 10 percent`() {
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2022-01-01", "-1000"),
                flow("2026-01-01", "1464.10"), // 1000 × 1.1⁴
            ),
            asOf = LocalDate.of(2026, 1, 1),
        )
        assertThat(result.value).isEqualByComparingTo(BigDecimal("0.1000"))
        assertThat(result.annualized).isTrue()
        assertThat(result.failure).isNull()
    }

    @Test
    fun `a losing position yields a negative annualized rate`() {
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2022-01-01", "-1000"),
                flow("2026-01-01", "656.10"), // 1000 × 0.9⁴
            ),
            asOf = LocalDate.of(2026, 1, 1),
        )
        assertThat(result.value).isEqualByComparingTo(BigDecimal("-0.1000"))
        assertThat(result.annualized).isTrue()
    }

    @Test
    fun `an interim sell inflow shifts the rate by its own date`() {
        // Two halves of a 1000 buy, each growing 10 %/y: one sold after 2 years
        // (500 × 1.1² = 605), the other held to the end (500 × 1.1⁴ = 732.05).
        // NPV(0.1) = −1000 + 605/1.1² + 732.05/1.1⁴ = −1000 + 500 + 500 = 0 exactly.
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2022-01-01", "-1000"),
                flow("2024-01-01", "605"),
                flow("2026-01-01", "732.05"),
            ),
            asOf = LocalDate.of(2026, 1, 1),
        )
        assertThat(result.value).isEqualByComparingTo(BigDecimal("0.1000"))
        assertThat(result.annualized).isTrue()
    }

    @Test
    fun `buy-sell-rebuy is where XIRR and return-on-cost visibly diverge`() {
        // Sell everything for 1100 and rebuy the same day: the recycled 1100 nets to
        // zero cash at that date, so XIRR sees 1000 in → 1464.10 out over 4 years = 10 %/y.
        // Return-on-cost for the same story counts the recycled money twice:
        // (realized 100 + unrealized 364.10) / (open 1100 + sold 1000) ≈ 22.1 %, timeless.
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2022-01-01", "-1000"),
                flow("2024-01-01", "1100"),
                flow("2024-01-01", "-1100"),
                flow("2026-01-01", "1464.10"),
            ),
            asOf = LocalDate.of(2026, 1, 1),
        )
        assertThat(result.value).isEqualByComparingTo(BigDecimal("0.1000"))
        assertThat(result.annualized).isTrue()
    }

    @Test
    fun `a history under one year reports the cumulative return, not an annualized one`() {
        // 198 days: the cumulative figure is exactly terminal/cost − 1, independent of
        // the annualize-then-compound round trip.
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2026-01-01", "-1000"),
                flow("2026-07-18", "1100"),
            ),
            asOf = LocalDate.of(2026, 7, 18),
        )
        assertThat(result.value).isEqualByComparingTo(BigDecimal("0.1000"))
        assertThat(result.annualized).isFalse()
        assertThat(result.failure).isNull()
    }

    @Test
    fun `a large short-span gain needs the bracket to expand beyond the initial ceiling`() {
        // +50 % in 30 days is ≈ +13 700 %/y annualized — far past the initial 1000 %
        // ceiling — yet the reported cumulative return is exactly 50 %.
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2026-06-18", "-1000"),
                flow("2026-07-18", "1500"),
            ),
            asOf = LocalDate.of(2026, 7, 18),
        )
        assertThat(result.value).isEqualByComparingTo(BigDecimal("0.5000"))
        assertThat(result.annualized).isFalse()
    }

    @Test
    fun `exactly one year of history is annualized`() {
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2025-07-18", "-1000"),
                flow("2026-07-18", "1100"),
            ),
            asOf = LocalDate.of(2026, 7, 18),
        )
        assertThat(result.annualized).isTrue()
        // 365 days is 0.99932 years under the 365.25 convention: r = 1.1^(1/0.99932) − 1.
        assertThat(result.value).isEqualByComparingTo(BigDecimal("0.1001"))
    }

    @Test
    fun `flows all pointing the same way have no rate`() {
        // Buys with a worthless terminal position: mathematically −100 %, but there is
        // no root to solve for — shown as unavailable, never a made-up number.
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2022-01-01", "-1000"),
                flow("2024-01-01", "-500"),
                flow("2026-01-01", "0"),
            ),
            asOf = LocalDate.of(2026, 1, 1),
        )
        assertThat(result.value).isNull()
        assertThat(result.failure).isEqualTo(Failure.NO_SIGN_CHANGE)
    }

    @Test
    fun `flows on a single day admit no rate`() {
        val result = MoneyWeightedReturn.compute(
            listOf(
                flow("2026-07-18", "-1000"),
                flow("2026-07-18", "1100"),
            ),
            asOf = LocalDate.of(2026, 7, 18),
        )
        assertThat(result.value).isNull()
        assertThat(result.failure).isEqualTo(Failure.SINGLE_DAY_SPAN)
    }

    @Test
    fun `zero-amount flows are ignored`() {
        val with = MoneyWeightedReturn.compute(
            listOf(
                flow("2022-01-01", "-1000"),
                flow("2023-06-15", "0"),
                flow("2026-01-01", "1464.10"),
            ),
            asOf = LocalDate.of(2026, 1, 1),
        )
        assertThat(with.value).isEqualByComparingTo(BigDecimal("0.1000"))
    }

    @Test
    fun `an empty flow list has no rate`() {
        val result = MoneyWeightedReturn.compute(emptyList(), asOf = LocalDate.of(2026, 1, 1))
        assertThat(result.value).isNull()
        assertThat(result.failure).isEqualTo(Failure.NO_SIGN_CHANGE)
    }
}
