package com.sephilabs.sharedledger.fire

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class XirrTest {

    @Test
    fun `simple one year gain is the plain return`() {
        val rate = Xirr.rate(
            listOf(
                Xirr.CashFlow(LocalDate.of(2024, 1, 1), -1000.0),
                Xirr.CashFlow(LocalDate.of(2025, 1, 1), 1100.0),
            )
        )
        assertThat(rate).isNotNull()
        assertThat(rate!!).isCloseTo(0.10, org.assertj.core.data.Offset.offset(0.001))
    }

    @Test
    fun `doubling over ten years annualizes to about seven percent`() {
        val rate = Xirr.rate(
            listOf(
                Xirr.CashFlow(LocalDate.of(2020, 1, 1), -1000.0),
                Xirr.CashFlow(LocalDate.of(2030, 1, 1), 2000.0),
            )
        )
        // 2^(1/10) − 1 ≈ 7.18%
        assertThat(rate!!).isCloseTo(0.0718, org.assertj.core.data.Offset.offset(0.002))
    }

    @Test
    fun `contributions are capital in not performance`() {
        // Terminal value equals exactly what was put in: the money-weighted return is zero,
        // even though the "portfolio" doubled between the first and last date.
        val rate = Xirr.rate(
            listOf(
                Xirr.CashFlow(LocalDate.of(2024, 1, 1), -10000.0),
                Xirr.CashFlow(LocalDate.of(2024, 7, 1), -10000.0),
                Xirr.CashFlow(LocalDate.of(2025, 1, 1), 20000.0),
            )
        )
        assertThat(rate!!).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001))
    }

    @Test
    fun `mid year contribution keeps return money weighted`() {
        // 10,000 grows plus a 5,000 mid-year contribution ends at 16,000:
        // 10000(1+r) + 5000(1+r)^0.5 = 16000  =>  r ≈ 8%.
        val rate = Xirr.rate(
            listOf(
                Xirr.CashFlow(LocalDate.of(2024, 1, 1), -10000.0),
                Xirr.CashFlow(LocalDate.of(2024, 7, 1), -5000.0),
                Xirr.CashFlow(LocalDate.of(2025, 1, 1), 16000.0),
            )
        )
        assertThat(rate!!).isBetween(0.07, 0.09)
    }

    @Test
    fun `no sign change means no rate`() {
        assertThat(
            Xirr.rate(
                listOf(
                    Xirr.CashFlow(LocalDate.of(2024, 1, 1), -1000.0),
                    Xirr.CashFlow(LocalDate.of(2025, 1, 1), -1000.0),
                )
            )
        ).isNull()
        assertThat(Xirr.rate(emptyList())).isNull()
    }

    @Test
    fun `losses produce a negative rate`() {
        val rate = Xirr.rate(
            listOf(
                Xirr.CashFlow(LocalDate.of(2024, 1, 1), -1000.0),
                Xirr.CashFlow(LocalDate.of(2025, 1, 1), 800.0),
            )
        )
        assertThat(rate!!).isCloseTo(-0.20, org.assertj.core.data.Offset.offset(0.001))
    }
}
