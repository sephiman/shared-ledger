package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.FxRate
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Import(StubPriceProviderConfig::class)
class PortfolioValuationIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val holdingService: HoldingService,
    private val service: PortfolioValuationService,
    private val prices: PricePointRepository,
    private val fxRates: FxRateRepository,
) : IntegrationTestBase() {

    @Test
    fun `valuation forward-fills the last known price across gaps`() {
        val (user, household) = seed()
        val holding = createLinkedCrypto(household.id, user, "BTC", "bitcoin")
        addLot(household.id, holding.id, user, LocalDate.of(2026, 1, 10), "0.5", "50000")

        storePrice("coingecko", "bitcoin", "EUR", LocalDate.of(2026, 2, 1), "60000")
        // Gap: nothing between Feb 1 and the valuation date.

        val valuation = service.valuationAt(household.id, LocalDate.of(2026, 2, 20))
        val row = valuation.holdings.single()
        assertThat(row.unitPrice).isEqualByComparingTo(BigDecimal("60000"))
        assertThat(row.priceAsOf).isEqualTo(LocalDate.of(2026, 2, 1))
        // 0.5 × 60000
        assertThat(row.valueBase).isEqualByComparingTo(BigDecimal("30000.00"))
        // 19 days old > 7-day threshold
        assertThat(row.stale).isTrue()
        assertThat(valuation.anyStale).isTrue()
        assertThat(valuation.byClass["crypto"]).isEqualByComparingTo(BigDecimal("30000.00"))
    }

    @Test
    fun `unlinked holdings value at zero and are flagged stale`() {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.fund, symbol = "MSCIW"),
            user,
        )
        addLot(household.id, holding.id, user, LocalDate.of(2026, 1, 1), "100", "95")

        val valuation = service.valuationAt(household.id, LocalDate.of(2026, 3, 1))
        val row = valuation.holdings.single()
        assertThat(row.unitPrice).isNull()
        assertThat(row.valueBase).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(row.stale).isTrue()
        assertThat(valuation.byClass["fund"]).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `non-EUR equity uses the fx rate at or before the valuation date`() {
        val (user, household) = seed()
        // Covers the lot's registration-time fx freeze; without it addLot fails on
        // LOT_FX_RATE_UNAVAILABLE (this test must not rely on rates leaked by other classes).
        seedUsdRate(LocalDate.of(2026, 1, 2), "0.88000000")
        seedUsdRate(LocalDate.of(2026, 2, 27), "0.90000000")
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.stock,
                symbol = "AAPL",
                nativeCurrency = "USD",
                provider = HoldingProvider.eodhd,
                providerSymbol = "AAPL.US",
            ),
            user,
        )
        addLot(household.id, holding.id, user, LocalDate.of(2026, 1, 5), "10", "180", currency = "USD")
        storePrice("eodhd", "AAPL.US", "USD", LocalDate.of(2026, 2, 27), "200")
        // A later rate must not be used for a Feb 28 valuation.
        seedUsdRate(LocalDate.of(2026, 3, 2), "0.95000000")

        val valuation = service.valuationAt(household.id, LocalDate.of(2026, 2, 28))
        val row = valuation.holdings.single()
        // 10 × 200 × 0.90
        assertThat(row.valueBase).isEqualByComparingTo(BigDecimal("1800.00"))
        assertThat(row.fxRate).isEqualByComparingTo(BigDecimal("0.90"))
        assertThat(row.stale).isFalse()
    }

    @Test
    fun `holding without a price and fx is excluded from summary totals but keeps its cost basis`() {
        val (user, household) = seed()
        // At (not after) the USD lot's trade date, so the frozen rate is deterministically 0.92.
        // now-1 would collide with BankIngestionIntegrationTest's identical (provider, pair, date)
        // row in the shared fx_rates table and resolve nothing for a now-10 lot anyway.
        seedUsdRate(LocalDate.now().minusDays(10), "0.92000000")
        val priced = createLinkedCrypto(household.id, user, "BTC", "bitcoin")
        addLot(household.id, priced.id, user, LocalDate.now().minusDays(30), "1", "50000")
        storePrice("coingecko", "bitcoin", "EUR", LocalDate.now(), "60000")

        val unpriced = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.stock,
                symbol = "NOPRICE",
                nativeCurrency = "USD",
                provider = HoldingProvider.eodhd,
                providerSymbol = "NOPRICE.US",
            ),
            user,
        )
        addLot(household.id, unpriced.id, user, LocalDate.now().minusDays(10), "10", "100", currency = "USD")

        val summary = service.summary(household.id)
        assertThat(summary.totalValue).isEqualByComparingTo(BigDecimal("60000.00"))
        // 50000 + (10 × 100 × 0.92)
        assertThat(summary.totalCostBasis).isEqualByComparingTo(BigDecimal("50920.00"))
        assertThat(summary.anyUnpriced).isTrue()
        val pricedRow = summary.holdings.single { it.holding.symbol == "BTC" }
        assertThat(pricedRow.weight).isEqualByComparingTo(BigDecimal.ONE)
        val unpricedRow = summary.holdings.single { it.holding.symbol == "NOPRICE" }
        assertThat(unpricedRow.currentValue).isNull()
        assertThat(unpricedRow.weight).isNull()
    }

    @Test
    fun `sells reduce the date-dependent quantity and split realized from unrealized`() {
        val (user, household) = seed()
        val coinId = "btc-sell-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "BTC", coinId)
        addLot(household.id, holding.id, user, LocalDate.of(2026, 1, 10), "2", "40000")
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = LocalDate.of(2026, 2, 10),
                quantity = BigDecimal("1"),
                unitPrice = BigDecimal("55000"),
            ),
            user,
        )
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 1, 15), "45000")
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 3, 1), "50000")

        // Before the sale: full quantity.
        val before = service.valuationAt(household.id, LocalDate.of(2026, 1, 31)).holdings.single()
        assertThat(before.quantity).isEqualByComparingTo(BigDecimal("2"))
        assertThat(before.valueBase).isEqualByComparingTo(BigDecimal("90000.00"))

        // After the sale: net quantity.
        val after = service.valuationAt(household.id, LocalDate.of(2026, 3, 5)).holdings.single()
        assertThat(after.quantity).isEqualByComparingTo(BigDecimal("1"))
        assertThat(after.valueBase).isEqualByComparingTo(BigDecimal("50000.00"))

        val summary = service.summary(household.id)
        val row = summary.holdings.single()
        // Realized: 1 × 55000 − FIFO cost 1 × 40000.
        assertThat(row.realizedPnl).isEqualByComparingTo(BigDecimal("15000.00"))
        assertThat(row.holding.remainingCostBasis).isEqualByComparingTo(BigDecimal("40000.00"))
        assertThat(row.unrealizedPnl).isEqualByComparingTo(BigDecimal("10000.00"))
        assertThat(row.totalReturn).isEqualByComparingTo(BigDecimal("25000.00"))
        assertThat(summary.totalRealizedPnl).isEqualByComparingTo(BigDecimal("15000.00"))
        assertThat(summary.totalCostBasis).isEqualByComparingTo(BigDecimal("40000.00"))
    }

    @Test
    fun `summary attributes per-lot realized and unrealized pnl with FIFO`() {
        val (user, household) = seed()
        val coinId = "btc-perlot-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "BTC", coinId)
        addLot(household.id, holding.id, user, LocalDate.of(2026, 1, 10), "2", "40000")
        addLot(household.id, holding.id, user, LocalDate.of(2026, 2, 1), "1", "50000")
        // Sells 1 BTC @ 55000: FIFO consumes it from the older (40000) buy.
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = LocalDate.of(2026, 3, 1),
                quantity = BigDecimal("1"),
                unitPrice = BigDecimal("55000"),
            ),
            user,
        )
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 3, 5), "60000")

        val row = service.summary(household.id).holdings.single()
        val buy1 = row.holding.lots.single { it.type == LotType.BUY && it.tradedOn == LocalDate.of(2026, 1, 10) }
        val buy2 = row.holding.lots.single { it.type == LotType.BUY && it.tradedOn == LocalDate.of(2026, 2, 1) }
        val sell = row.holding.lots.single { it.type == LotType.SELL }

        // Older buy: 1 of its 2 units sold @ 55000 (realized 15000), 1 still held.
        assertThat(buy1.remainingQty).isEqualByComparingTo(BigDecimal("1"))
        assertThat(buy1.remainingCostBasis).isEqualByComparingTo(BigDecimal("40000.00"))
        assertThat(buy1.realizedPnl).isEqualByComparingTo(BigDecimal("15000.00"))
        // Unrealized on the held unit at the 60000 price.
        assertThat(buy1.unrealizedPnl).isEqualByComparingTo(BigDecimal("20000.00"))
        // Newer buy: untouched by the sell, fully held.
        assertThat(buy2.remainingQty).isEqualByComparingTo(BigDecimal("1"))
        assertThat(buy2.realizedPnl).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(buy2.unrealizedPnl).isEqualByComparingTo(BigDecimal("10000.00"))
        // The sell shows its own realized P&L; remaining/unrealized are not meaningful for it.
        assertThat(sell.realizedPnl).isEqualByComparingTo(BigDecimal("15000.00"))
        assertThat(sell.remainingQty).isNull()
        assertThat(sell.remainingCostBasis).isNull()
        assertThat(sell.unrealizedPnl).isNull()

        // Per-lot figures reconcile with the holding-level totals shown in the row.
        val buyUnrealized = buy1.unrealizedPnl!!.add(buy2.unrealizedPnl!!)
        val buyRealized = buy1.realizedPnl!!.add(buy2.realizedPnl!!)
        assertThat(buyUnrealized).isEqualByComparingTo(row.unrealizedPnl)
        assertThat(buyRealized).isEqualByComparingTo(row.realizedPnl)
    }

    @Test
    fun `closed positions stay listed with realized pnl and zero value`() {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.fund, symbol = "CLOSED"),
            user,
        )
        addLot(household.id, holding.id, user, LocalDate.of(2026, 1, 5), "10", "100")
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = LocalDate.of(2026, 2, 5),
                quantity = BigDecimal("10"),
                unitPrice = BigDecimal("150"),
            ),
            user,
        )

        val summary = service.summary(household.id)
        val row = summary.holdings.single()
        assertThat(row.holding.closed).isTrue()
        assertThat(row.holding.netQuantity).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(row.currentValue).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(row.realizedPnl).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(summary.totalRealizedPnl).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(summary.totalValue).isEqualByComparingTo(BigDecimal.ZERO)

        // A position closed by the valuation date holds nothing worth freezing.
        assertThat(service.valuationAt(household.id, LocalDate.of(2026, 3, 1)).holdings).isEmpty()
    }

    @Test
    fun `the invested evolution line drops on a sell`() {
        val (user, household) = seed()
        val coinId = "btc-inv-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "BTC", coinId)
        addLot(household.id, holding.id, user, LocalDate.of(2026, 4, 1), "2", "10000")
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = LocalDate.of(2026, 4, 3),
                quantity = BigDecimal("1"),
                unitPrice = BigDecimal("12000"),
            ),
            user,
        )
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 4, 1), "10000")

        val evolution = service.evolution(household.id, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 4))
        // Before the sell: invested = full cost basis, nothing realized, flat price.
        assertThat(evolution.points[0].invested).isEqualByComparingTo(BigDecimal("20000.00"))
        assertThat(evolution.points[0].realizedPnl).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(evolution.points[0].unrealizedPnl).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(evolution.points[1].invested).isEqualByComparingTo(BigDecimal("20000.00"))
        // From the sell on: only the remaining FIFO cost; realized = 12000 − 10000.
        assertThat(evolution.points[2].invested).isEqualByComparingTo(BigDecimal("10000.00"))
        assertThat(evolution.points[2].value).isEqualByComparingTo(BigDecimal("10000.00"))
        assertThat(evolution.points[2].realizedPnl).isEqualByComparingTo(BigDecimal("2000.00"))
        assertThat(evolution.points[2].unrealizedPnl).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(evolution.points[3].invested).isEqualByComparingTo(BigDecimal("10000.00"))
        assertThat(evolution.points[3].realizedPnl).isEqualByComparingTo(BigDecimal("2000.00"))
    }

    @Test
    fun `evolution filters by asset class and by holding`() {
        val (user, household) = seed()
        val coinId = "btc-filter-${System.nanoTime()}"
        val crypto = createLinkedCrypto(household.id, user, "BTC", coinId)
        addLot(household.id, crypto.id, user, LocalDate.of(2026, 5, 1), "1", "10000")
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 5, 1), "10000")
        val fund = holdingService.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.fund, symbol = "FLT"),
            user,
        )
        addLot(household.id, fund.id, user, LocalDate.of(2026, 5, 1), "10", "50")

        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 2)

        val unfiltered = service.evolution(household.id, from, to)
        assertThat(unfiltered.points[0].invested).isEqualByComparingTo(BigDecimal("10500.00"))

        val cryptoOnly = service.evolution(household.id, from, to, assetClass = HoldingAssetClass.crypto)
        assertThat(cryptoOnly.points[0].invested).isEqualByComparingTo(BigDecimal("10000.00"))
        assertThat(cryptoOnly.points[0].value).isEqualByComparingTo(BigDecimal("10000.00"))

        val fundOnly = service.evolution(household.id, from, to, holdingId = fund.id)
        assertThat(fundOnly.points[0].invested).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(fundOnly.points[0].value).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `long evolution ranges sample coarser but always include the end date`() {
        val (user, household) = seed()
        val coinId = "btc-long-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "BTC", coinId)
        val start = LocalDate.of(2020, 1, 1)
        val end = LocalDate.of(2026, 6, 30)
        addLot(household.id, holding.id, user, start, "1", "5000")
        storePrice("coingecko", coinId, "EUR", start, "5000")

        val evolution = service.evolution(household.id, start, end)

        assertThat(evolution.points.size).isLessThanOrEqualTo(PortfolioValuationService.MAX_EVOLUTION_POINTS.toInt() + 1)
        assertThat(evolution.points.first().date).isEqualTo(start)
        assertThat(evolution.points.last().date).isEqualTo(end)
        // Forward-fill carries the only observation across the whole range.
        assertThat(evolution.points.last().value).isEqualByComparingTo(BigDecimal("5000.00"))
    }

    @Test
    fun `evolution builds a daily value curve with an invested line`() {
        val (user, household) = seed()
        val holding = createLinkedCrypto(household.id, user, "BTC", "bitcoin")
        addLot(household.id, holding.id, user, LocalDate.of(2026, 3, 1), "1", "50000")
        addLot(household.id, holding.id, user, LocalDate.of(2026, 3, 3), "1", "55000")
        storePrice("coingecko", "bitcoin", "EUR", LocalDate.of(2026, 3, 1), "50000")
        storePrice("coingecko", "bitcoin", "EUR", LocalDate.of(2026, 3, 3), "60000")

        val evolution = service.evolution(household.id, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4))
        assertThat(evolution.points).hasSize(4)

        val day1 = evolution.points[0]
        assertThat(day1.value).isEqualByComparingTo(BigDecimal("50000.00"))
        assertThat(day1.invested).isEqualByComparingTo(BigDecimal("50000.00"))

        // Day 2 forward-fills the day-1 price.
        val day2 = evolution.points[1]
        assertThat(day2.value).isEqualByComparingTo(BigDecimal("50000.00"))

        // Day 3: 2 BTC at 60000, invested 105000.
        val day3 = evolution.points[2]
        assertThat(day3.value).isEqualByComparingTo(BigDecimal("120000.00"))
        assertThat(day3.invested).isEqualByComparingTo(BigDecimal("105000.00"))

        val day4 = evolution.points[3]
        assertThat(day4.value).isEqualByComparingTo(BigDecimal("120000.00"))
    }

    @Test
    fun `TWR chains market returns and excludes the timing of contributions`() {
        val (user, household) = seed()
        val coinId = "btc-twr-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "BTC", coinId)
        // Buy 1 @ 100, price rises to 110, buy a 2nd unit at the market price of 110,
        // price rises to 121. The mid-range contribution must not register as return.
        addLot(household.id, holding.id, user, LocalDate.of(2026, 3, 1), "1", "100")
        addLot(household.id, holding.id, user, LocalDate.of(2026, 3, 3), "1", "110")
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 3, 1), "100")
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 3, 2), "110")
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 3, 3), "110")
        storePrice("coingecko", coinId, "EUR", LocalDate.of(2026, 3, 4), "121")

        val evolution = service.evolution(household.id, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4))
        assertThat(evolution.points).hasSize(4)

        // Start of the range is the base: 0 %.
        assertThat(evolution.points[0].twrPct).isEqualByComparingTo(BigDecimal.ZERO)
        // +10 % as the price moves 100 -> 110.
        assertThat(evolution.points[1].twrPct).isEqualByComparingTo(BigDecimal("0.10"))
        // Buying a 2nd unit at the market price leaves TWR unchanged: still +10 %.
        assertThat(evolution.points[2].twrPct).isEqualByComparingTo(BigDecimal("0.10"))
        // 110 -> 121 chains another +10 %: (1.10 * 1.10) - 1 = +21 %.
        assertThat(evolution.points[3].twrPct).isEqualByComparingTo(BigDecimal("0.21"))
    }

    private fun createLinkedCrypto(householdId: java.util.UUID, user: User, symbol: String, providerSymbol: String) =
        holdingService.create(
            householdId,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = symbol,
                provider = HoldingProvider.coingecko,
                providerSymbol = providerSymbol,
            ),
            user,
        )

    private fun addLot(
        householdId: java.util.UUID,
        holdingId: java.util.UUID,
        user: User,
        acquiredOn: LocalDate,
        quantity: String,
        unitPrice: String,
        currency: String? = null,
    ) = holdingService.addLot(
        householdId, holdingId,
        LotRequest(
            tradedOn = acquiredOn,
            quantity = BigDecimal(quantity),
            unitPrice = BigDecimal(unitPrice),
            currency = currency,
        ),
        user,
    )

    private fun storePrice(provider: String, symbol: String, currency: String, date: LocalDate, price: String) {
        prices.save(
            PricePoint(
                provider = provider,
                providerSymbol = symbol,
                currency = currency,
                price = BigDecimal(price),
                priceDate = date,
                asOf = Instant.now(),
            )
        )
    }

    private fun seedUsdRate(date: LocalDate, rate: String) {
        fxRates.save(
            FxRate(
                provider = "frankfurter",
                baseCurrency = "USD",
                quoteCurrency = "EUR",
                rate = BigDecimal(rate),
                rateDate = date,
            )
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "pv${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
