package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import com.sephilabs.sharedledger.portfolio.price.PriceRefreshService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.LocalDate

@Import(StubPriceProviderConfig::class)
class PriceRefreshIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val holdingService: HoldingService,
    private val refresh: PriceRefreshService,
    private val prices: PricePointRepository,
    private val stubCrypto: StubCryptoProvider,
    private val stubEquity: StubEquityProvider,
    private val stubBinance: StubCryptoFallback,
    private val stubFx: StubFxProvider,
    private val fxRates: com.sephilabs.sharedledger.portfolio.price.FxRateRepository,
) : IntegrationTestBase() {

    @Test
    fun `crypto refresh gap-fills missed days and upserts today's row intraday`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val symbol = "GAP${System.nanoTime()}"
        val coinId = "gap-coin-${System.nanoTime()}"
        createLinkedCrypto(household.id, user, symbol, coinId)

        // Seed a stored price 3 days back, with the stub able to serve the gap.
        refreshSeedPrice(coinId, today.minusDays(3), "100")
        stubCrypto.history[coinId] = mutableListOf(
            DailyPrice(today.minusDays(2), BigDecimal("102")),
            DailyPrice(today.minusDays(1), BigDecimal("104")),
        )
        stubCrypto.current[coinId] = BigDecimal("110")

        refresh.refreshCrypto(today)

        val stored = prices.findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
            "coingecko", coinId, "EUR", today.minusDays(3), today,
        )
        assertThat(stored.map { it.priceDate }).containsExactly(
            today.minusDays(3), today.minusDays(2), today.minusDays(1), today,
        )
        assertThat(stored.last().price).isEqualByComparingTo(BigDecimal("110"))

        // Intraday: a later run updates today's row instead of duplicating it.
        stubCrypto.current[coinId] = BigDecimal("115")
        refresh.refreshCrypto(today)
        val updated = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", coinId, "EUR", today)
        assertThat(updated!!.price).isEqualByComparingTo(BigDecimal("115"))
        assertThat(
            prices.findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
                "coingecko", coinId, "EUR", today, today,
            )
        ).hasSize(1)
    }

    @Test
    fun `a failing symbol does not abort the refresh of the others`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val badId = "bad-coin-${System.nanoTime()}"
        val goodId = "good-coin-${System.nanoTime()}"
        createLinkedCrypto(household.id, user, "BAD${System.nanoTime() % 1000}", badId)
        createLinkedCrypto(household.id, user, "GOOD${System.nanoTime() % 1000}", goodId)

        refreshSeedPrice(badId, today.minusDays(2), "10")
        refreshSeedPrice(goodId, today.minusDays(2), "20")
        stubCrypto.failFor += badId
        stubCrypto.history[goodId] = mutableListOf(DailyPrice(today.minusDays(1), BigDecimal("22")))
        stubCrypto.current[goodId] = BigDecimal("25")

        refresh.refreshCrypto(today)

        assertThat(prices.findMaxPriceDate("coingecko", goodId, "EUR")).isEqualTo(today)
        assertThat(prices.findMaxPriceDate("coingecko", badId, "EUR")).isEqualTo(today.minusDays(2))
    }

    @Test
    fun `equity refresh double run is idempotent`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val symbol = "EQ${System.nanoTime() % 100000}"
        holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = symbol,
                // The refresh only touches holdings linked to the active provider (yahoo by default).
                provider = HoldingProvider.yahoo,
                providerSymbol = "$symbol.DE",
            ),
            user,
        )
        holdingService.addLot(
            household.id,
            holdingService.list(household.id).single { it.symbol == symbol }.id,
            LotRequest(tradedOn = today.minusDays(5), quantity = BigDecimal("10"), unitPrice = BigDecimal("100")),
            user,
        )
        stubEquity.history["$symbol.DE"] = mutableListOf(
            DailyPrice(today.minusDays(5), BigDecimal("100")),
            DailyPrice(today.minusDays(2), BigDecimal("101")),
            DailyPrice(today, BigDecimal("103")),
        )

        refresh.refreshEquities(today)
        refresh.refreshEquities(today)

        val stored = prices.findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
            "yahoo", "$symbol.DE", "EUR", today.minusDays(5), today,
        )
        assertThat(stored).hasSize(3)
        assertThat(stored.last().price).isEqualByComparingTo(BigDecimal("103"))
    }

    @Test
    fun `binance fills crypto history beyond the coingecko ceiling, converted to base currency`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "old-coin-${System.nanoTime()}"
        val ticker = "OLD${System.nanoTime() % 1000}"
        val holding = createLinkedCrypto(household.id, user, ticker, coinId)

        // CoinGecko serves only inside the 365-day ceiling; Binance has the older head.
        stubCrypto.history[coinId] = mutableListOf(DailyPrice(today.minusDays(10), BigDecimal("50000")))
        stubBinance.history["${ticker}USDT"] = mutableListOf(
            DailyPrice(today.minusDays(500), BigDecimal("20000")),
        )
        stubFx.history["USD"] = mutableListOf(
            DailyPrice(today.minusDays(505), BigDecimal("0.90")),
        )

        // Lot far beyond the ceiling triggers the fallback for the pre-ceiling head.
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(500), quantity = BigDecimal("1"), unitPrice = BigDecimal("18000")),
            user,
        )

        val call = stubBinance.calls.single { it.first == "${ticker}USDT" }
        assertThat(call.second).isEqualTo(today.minusDays(500))
        assertThat(call.third).isEqualTo(today.minusDays(366))
        // Stored in the SAME coingecko/EUR series, converted: 20000 USD × 0.90.
        val old = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate(
            "coingecko", coinId, "EUR", today.minusDays(500),
        )
        assertThat(old!!.price).isEqualByComparingTo(BigDecimal("18000"))
        assertThat(prices.findMinPriceDate("coingecko", coinId, "EUR")).isEqualTo(today.minusDays(500))
    }

    @Test
    fun `binance covers the gap-fill when coingecko is down`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "down-coin-${System.nanoTime()}"
        val ticker = "DWN${System.nanoTime() % 1000}"
        createLinkedCrypto(household.id, user, ticker, coinId)

        refreshSeedPrice(coinId, today.minusDays(5), "100")
        stubCrypto.failFor += coinId
        stubBinance.history["${ticker}USDT"] = mutableListOf(
            DailyPrice(today.minusDays(4), BigDecimal("110")),
            DailyPrice(today.minusDays(3), BigDecimal("120")),
        )
        // Deterministic conversion rate: the latest stored USD rate <= the fallback days,
        // regardless of what other tests put into the shared fx_rates table.
        fxRates.save(
            com.sephilabs.sharedledger.portfolio.price.FxRate(
                provider = "frankfurter",
                baseCurrency = "USD",
                quoteCurrency = "EUR",
                rate = BigDecimal("0.90000000"),
                rateDate = today.minusDays(4),
            )
        )

        refresh.refreshCrypto(today)

        // 110 × 0.90 and 120 × 0.90 landed despite the CoinGecko failure.
        assertThat(
            prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", coinId, "EUR", today.minusDays(4))!!.price,
        ).isEqualByComparingTo(BigDecimal("99"))
        assertThat(
            prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", coinId, "EUR", today.minusDays(3))!!.price,
        ).isEqualByComparingTo(BigDecimal("108"))
    }

    @Test
    fun `the provider-reported currency corrects a wrong native currency`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val symbol = "USD${System.nanoTime() % 100000}"
        // Yahoo search returns no currency, so the holding was created with the EUR default —
        // but the chart meta says the listing actually trades in USD.
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.stock,
                symbol = symbol,
                provider = HoldingProvider.yahoo,
                providerSymbol = symbol,
            ),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(3), quantity = BigDecimal("5"), unitPrice = BigDecimal("100")),
            user,
        )
        stubEquity.currencies[symbol] = "USD"
        stubEquity.history[symbol] = mutableListOf(DailyPrice(today.minusDays(1), BigDecimal("110")))

        refresh.refreshEquities(today)

        val corrected = holdingService.get(household.id, holding.id)
        assertThat(corrected.nativeCurrency).isEqualTo("USD")
        // Prices are stored under the real currency, not the wrong EUR label.
        assertThat(prices.findMaxPriceDate("yahoo", symbol, "USD")).isEqualTo(today.minusDays(1))
        assertThat(prices.findMaxPriceDate("yahoo", symbol, "EUR")).isNull()
    }

    @Test
    fun `equity nightly fills a head gap a failed backfill left behind`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val symbol = "HEAD${System.nanoTime() % 100000}"
        val ps = "$symbol.DE"
        holdingService.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.etf, symbol = symbol, provider = HoldingProvider.yahoo, providerSymbol = ps),
            user,
        )
        val holdingId = holdingService.list(household.id).single { it.symbol == symbol }.id
        // A recent tail is stored, but the older lot's head never made it (provider was down then).
        seedEquityPrice(ps, today.minusDays(3), "100")
        holdingService.addLot(
            household.id, holdingId,
            LotRequest(tradedOn = today.minusDays(10), quantity = BigDecimal("10"), unitPrice = BigDecimal("95")),
            user,
        )
        assertThat(prices.findMinPriceDate("yahoo", ps, "EUR")).isEqualTo(today.minusDays(3))

        // The provider now serves the history; the nightly job must close the head gap.
        stubEquity.history[ps] = mutableListOf(
            DailyPrice(today.minusDays(10), BigDecimal("95")),
            DailyPrice(today.minusDays(3), BigDecimal("100")),
            DailyPrice(today, BigDecimal("103")),
        )

        refresh.refreshEquities(today)

        assertThat(prices.findMinPriceDate("yahoo", ps, "EUR")).isEqualTo(today.minusDays(10))
        assertThat(prices.findMaxPriceDate("yahoo", ps, "EUR")).isEqualTo(today)
    }

    @Test
    fun `crypto nightly bootstraps a series the request-time backfill never populated`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "boot-coin-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "BOOT${System.nanoTime() % 1000}", coinId)
        // Lot exists but the backfill at add-time stored nothing (provider was down / not seeded yet).
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(5), quantity = BigDecimal("1"), unitPrice = BigDecimal("100")),
            user,
        )
        assertThat(prices.findMinPriceDate("coingecko", coinId, "EUR")).isNull()

        stubCrypto.history[coinId] = mutableListOf(
            DailyPrice(today.minusDays(5), BigDecimal("100")),
            DailyPrice(today.minusDays(1), BigDecimal("110")),
        )
        stubCrypto.current[coinId] = BigDecimal("115")

        refresh.refreshCrypto(today)

        assertThat(prices.findMinPriceDate("coingecko", coinId, "EUR")).isEqualTo(today.minusDays(5))
        assertThat(prices.findMaxPriceDate("coingecko", coinId, "EUR")).isEqualTo(today)
    }

    @Test
    fun `crypto nightly fills a head gap down to an earlier lot`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "chead-coin-${System.nanoTime()}"
        val holding = createLinkedCrypto(household.id, user, "CHD${System.nanoTime() % 1000}", coinId)
        refreshSeedPrice(coinId, today.minusDays(2), "100")
        // Older lot added while the provider had no head to give: the gap persists after add.
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(20), quantity = BigDecimal("1"), unitPrice = BigDecimal("80")),
            user,
        )
        assertThat(prices.findMinPriceDate("coingecko", coinId, "EUR")).isEqualTo(today.minusDays(2))

        stubCrypto.history[coinId] = mutableListOf(DailyPrice(today.minusDays(20), BigDecimal("80")))

        refresh.refreshCrypto(today)

        assertThat(prices.findMinPriceDate("coingecko", coinId, "EUR")).isEqualTo(today.minusDays(20))
    }

    private fun seedEquityPrice(providerSymbol: String, date: LocalDate, price: String) {
        prices.save(
            com.sephilabs.sharedledger.portfolio.price.PricePoint(
                provider = "yahoo",
                providerSymbol = providerSymbol,
                currency = "EUR",
                price = BigDecimal(price),
                priceDate = date,
                asOf = java.time.Instant.now(),
            )
        )
    }

    private fun createLinkedCrypto(householdId: java.util.UUID, user: User, symbol: String, coinId: String) =
        holdingService.create(
            householdId,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = symbol,
                provider = HoldingProvider.coingecko,
                providerSymbol = coinId,
            ),
            user,
        )

    private fun refreshSeedPrice(coinId: String, date: LocalDate, price: String) {
        prices.save(
            com.sephilabs.sharedledger.portfolio.price.PricePoint(
                provider = "coingecko",
                providerSymbol = coinId,
                currency = "EUR",
                price = BigDecimal(price),
                priceDate = date,
                asOf = java.time.Instant.now(),
            )
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "pr${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
