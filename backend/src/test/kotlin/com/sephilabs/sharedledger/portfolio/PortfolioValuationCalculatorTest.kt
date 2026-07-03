package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.config.AppProperties.CostMethod
import com.sephilabs.sharedledger.portfolio.PortfolioValuationCalculator.LedgerEntry
import com.sephilabs.sharedledger.portfolio.PortfolioValuationCalculator.PriceInput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class PortfolioValuationCalculatorTest {

    private val asOf = LocalDate.of(2026, 6, 30)

    private fun buy(day: Int, qty: String, price: String, fee: String? = null, fx: String = "1") =
        LedgerEntry(LotType.BUY, asOf.minusDays(200L - day), BigDecimal(qty), BigDecimal(price), fee?.let(::BigDecimal), BigDecimal(fx))

    private fun sell(day: Int, qty: String, price: String, fee: String? = null, fx: String = "1") =
        LedgerEntry(LotType.SELL, asOf.minusDays(200L - day), BigDecimal(qty), BigDecimal(price), fee?.let(::BigDecimal), BigDecimal(fx))

    @Test
    fun `buys accumulate quantity and cost basis with fees at the frozen fx rate`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                // (10 × 100 + 5) × 1 = 1005
                buy(1, "10", "100", fee = "5"),
                // (2 × 190 + 1) × 0.92 = 350.52
                buy(2, "2", "190", fee = "1", fx = "0.92"),
            )
        )
        assertThat(state.netQuantity).isEqualByComparingTo(BigDecimal("12"))
        assertThat(state.remainingCostBasisBase).isEqualByComparingTo(BigDecimal("1355.52"))
        assertThat(state.realizedPnlBase).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `FIFO sell consumes across multiple lots and realizes the difference`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                buy(1, "10", "100"),           // per-unit 100
                buy(2, "10", "200"),           // per-unit 200
                // Sells 15 @ 300 with 10 fee: consumes lot1 fully + 5 of lot2.
                // proceeds = 15×300 − 10 = 4490; cost = 10×100 + 5×200 = 2000; realized = 2490.
                sell(3, "15", "300", fee = "10"),
            )
        )
        assertThat(state.netQuantity).isEqualByComparingTo(BigDecimal("5"))
        // Remaining: 5 units of lot2 at 200.
        assertThat(state.remainingCostBasisBase).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(state.realizedPnlBase).isEqualByComparingTo(BigDecimal("2490.00"))
    }

    @Test
    fun `partial sell prorates the purchase fee of the consumed lot`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                // per-unit cost = (10×100 + 20) / 10 = 102
                buy(1, "10", "100", fee = "20"),
                // proceeds = 4×110 = 440; cost = 4×102 = 408; realized = 32.
                sell(2, "4", "110"),
            )
        )
        assertThat(state.netQuantity).isEqualByComparingTo(BigDecimal("6"))
        assertThat(state.remainingCostBasisBase).isEqualByComparingTo(BigDecimal("612.00"))
        assertThat(state.realizedPnlBase).isEqualByComparingTo(BigDecimal("32.00"))
    }

    @Test
    fun `full sell closes the position but keeps realized history`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                buy(1, "3", "50"),
                sell(2, "3", "80"),
            )
        )
        assertThat(state.netQuantity).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(state.remainingCostBasisBase).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(state.realizedPnlBase).isEqualByComparingTo(BigDecimal("90.00"))
    }

    @Test
    fun `sale fx is frozen at the sale date, independent of the purchase fx`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                // cost base = 10 × 100 × 0.90 = 900
                buy(1, "10", "100", fx = "0.90"),
                // proceeds = (10 × 120) × 0.95 = 1140; realized = 240.
                sell(2, "10", "120", fx = "0.95"),
            )
        )
        assertThat(state.realizedPnlBase).isEqualByComparingTo(BigDecimal("240.00"))
    }

    @Test
    fun `replay up to a date gives the date-dependent net quantity`() {
        val entries = listOf(
            buy(1, "10", "100"),
            sell(5, "4", "150"),
            buy(9, "2", "120"),
        )
        assertThat(PortfolioValuationCalculator.netQuantityAt(entries, asOf.minusDays(199)))
            .isEqualByComparingTo(BigDecimal("10"))
        assertThat(PortfolioValuationCalculator.netQuantityAt(entries, asOf.minusDays(195)))
            .isEqualByComparingTo(BigDecimal("6"))
        assertThat(PortfolioValuationCalculator.netQuantityAt(entries, asOf))
            .isEqualByComparingTo(BigDecimal("8"))
        // Before any trade: nothing held.
        assertThat(PortfolioValuationCalculator.netQuantityAt(entries, asOf.minusDays(300)))
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `same-day buy and sell replay with the buy first`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                // SELL listed first but same date as the BUY: must not oversell.
                sell(1, "5", "110"),
                buy(1, "5", "100"),
            ),
            strict = true,
        )
        assertThat(state.netQuantity).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(state.realizedPnlBase).isEqualByComparingTo(BigDecimal("50.00"))
    }

    @Test
    fun `strict replay rejects an oversell`() {
        assertThatThrownBy {
            PortfolioValuationCalculator.replay(
                listOf(buy(1, "5", "100"), sell(2, "6", "100")),
                strict = true,
            )
        }.isInstanceOf(PortfolioValuationCalculator.OversellException::class.java)
    }

    @Test
    fun `the AVERAGE cost method is not implemented yet`() {
        assertThatThrownBy {
            PortfolioValuationCalculator.replay(listOf(buy(1, "1", "1")), method = CostMethod.AVERAGE)
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `value splits realized and unrealized and sums the total return`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(
                buy(1, "10", "100"),
                sell(2, "5", "150"),   // realized = 250
            )
        )
        val result = PortfolioValuationCalculator.value(
            state, PriceInput(BigDecimal("180"), asOf, "EUR", BigDecimal.ONE), asOf, staleThresholdDays = 7,
        )
        assertThat(result.netQuantity).isEqualByComparingTo(BigDecimal("5"))
        assertThat(result.remainingCostBasisBase).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(result.currentValueBase).isEqualByComparingTo(BigDecimal("900.00"))
        assertThat(result.unrealizedPnlAbs).isEqualByComparingTo(BigDecimal("400.00"))
        // 400 / 500
        assertThat(result.unrealizedPnlPct).isEqualByComparingTo(BigDecimal("0.8"))
        assertThat(result.realizedPnlBase).isEqualByComparingTo(BigDecimal("250.00"))
        assertThat(result.totalReturnBase).isEqualByComparingTo(BigDecimal("650.00"))
        assertThat(result.stale).isFalse()
    }

    @Test
    fun `closed positions value at zero even without a price`() {
        val state = PortfolioValuationCalculator.replay(
            listOf(buy(1, "3", "50"), sell(2, "3", "80")),
        )
        val result = PortfolioValuationCalculator.value(state, price = null, asOfDate = asOf, staleThresholdDays = 7)
        assertThat(result.currentValueBase).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.unrealizedPnlAbs).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.realizedPnlBase).isEqualByComparingTo(BigDecimal("90.00"))
        assertThat(result.totalReturnBase).isEqualByComparingTo(BigDecimal("90.00"))
        assertThat(result.stale).isFalse()
    }

    @Test
    fun `missing price on an open position yields no value and marks it stale`() {
        val state = PortfolioValuationCalculator.replay(listOf(buy(1, "1", "100")))
        val result = PortfolioValuationCalculator.value(state, price = null, asOfDate = asOf, staleThresholdDays = 7)
        assertThat(result.currentValueBase).isNull()
        assertThat(result.unrealizedPnlAbs).isNull()
        assertThat(result.totalReturnBase).isNull()
        assertThat(result.stale).isTrue()
        assertThat(result.remainingCostBasisBase).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `stale threshold boundary is inclusive`() {
        val state = PortfolioValuationCalculator.replay(listOf(buy(1, "1", "1")))
        val onThreshold = PriceInput(BigDecimal.ONE, asOf.minusDays(7), "EUR", BigDecimal.ONE)
        val pastThreshold = PriceInput(BigDecimal.ONE, asOf.minusDays(8), "EUR", BigDecimal.ONE)
        assertThat(PortfolioValuationCalculator.value(state, onThreshold, asOf, 7).stale).isFalse()
        assertThat(PortfolioValuationCalculator.value(state, pastThreshold, asOf, 7).stale).isTrue()
    }

    @Test
    fun `weights are fractions of the priced total`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val weights = PortfolioValuationCalculator.weights(
            mapOf(a to BigDecimal("750.00"), b to BigDecimal("250.00"))
        )
        assertThat(weights[a]).isEqualByComparingTo(BigDecimal("0.75"))
        assertThat(weights[b]).isEqualByComparingTo(BigDecimal("0.25"))
        assertThat(PortfolioValuationCalculator.weights(emptyMap())).isEmpty()
    }
}
