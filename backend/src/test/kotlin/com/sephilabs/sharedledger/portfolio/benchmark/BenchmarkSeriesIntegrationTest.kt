package com.sephilabs.sharedledger.portfolio.benchmark

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.price.FxRate
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Uses test-only benchmark keys and a currency (SEK) no other test touches: fx_rates and benchmark_price
 *  are shared across the whole integration-test run, so seeded keys would collide across classes. */
class BenchmarkSeriesIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val holdingService: HoldingService,
    private val service: BenchmarkService,
    private val benchmarks: BenchmarkRepository,
    private val benchmarkPrices: BenchmarkPriceRepository,
    private val fxRates: FxRateRepository,
) : IntegrationTestBase() {

    private val from = LocalDate.of(2026, 1, 1)
    private val d2 = LocalDate.of(2026, 1, 2)
    private val d3 = LocalDate.of(2026, 1, 3)
    private val to = LocalDate.of(2026, 1, 4)

    @Test
    fun `normalizes a benchmark to 0 percent at the window start in EUR terms`() {
        val (_, household) = seedWithWindow()
        val key = insertBenchmark("bench_fx", currency = "SEK")
        // 100 -> 110 -> (gap) -> 120, fed through SEK->EUR rates that change mid-window, so a
        // correct series must embed the FX move, not just the price ratio.
        storeClose(key, from, "100")
        storeClose(key, d2, "110")
        storeClose(key, to, "120")
        seedRate("SEK", from, "0.90")
        seedRate("SEK", d3, "1.00")

        val series = service.series(household.id, from, to, null, null, listOf(key)).series.single()

        assertThat(series.partial).isFalse()
        assertThat(series.availableFrom).isEqualTo(from)
        val pts = series.points
        // EUR: 90, 99, 110 (fx forward-fills 1.00 from Jan 3), 120 -> re-based to Jan 1 = 90.
        assertThat(pts[0].twrPct).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(pts[1].twrPct).isEqualByComparingTo(BigDecimal("0.10")) // 99/90 - 1
        assertThat(pts[2].twrPct).isEqualByComparingTo(BigDecimal("0.2222")) // 110/90 - 1
        assertThat(pts[3].twrPct).isEqualByComparingTo(BigDecimal("0.3333")) // 120/90 - 1
    }

    @Test
    fun `marks a benchmark partial and leaves the leading gap blank when data starts late`() {
        val (_, household) = seedWithWindow()
        // EUR benchmark (no FX needed); data only from Jan 3, so Jan 1-2 stay blank, never faked.
        val key = insertBenchmark("bench_gap", currency = "EUR")
        storeClose(key, d3, "200")
        storeClose(key, to, "220")

        val series = service.series(household.id, from, to, null, null, listOf(key)).series.single()

        assertThat(series.partial).isTrue()
        assertThat(series.availableFrom).isEqualTo(d3)
        val pts = series.points
        assertThat(pts[0].twrPct).isNull()
        assertThat(pts[1].twrPct).isNull()
        assertThat(pts[2].twrPct).isEqualByComparingTo(BigDecimal.ZERO) // anchor at first available
        assertThat(pts[3].twrPct).isEqualByComparingTo(BigDecimal("0.10")) // 220/200 - 1
    }

    @Test
    fun `returns no series when there is no TWR curve to overlay`() {
        val (_, household) = seed() // household with no holdings/lots
        val key = insertBenchmark("bench_none", currency = "EUR")
        storeClose(key, from, "100")

        val response = service.series(household.id, from, to, null, null, listOf(key))
        assertThat(response.series).isEmpty()
    }

    /** A crypto holding with a lot so the TWR window resolves to [from]..[to]. */
    private fun seedWithWindow(): Pair<User, Household> {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "BTC",
                provider = HoldingProvider.coingecko,
                providerSymbol = "bitcoin-${System.nanoTime()}",
            ),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = from, quantity = BigDecimal("1"), unitPrice = BigDecimal("10000")),
            user,
        )
        return user to household
    }

    private fun insertBenchmark(key: String, currency: String): String {
        benchmarks.save(
            Benchmark(
                key = key,
                sourceProvider = "yahoo",
                sourceSymbol = "TEST",
                currency = currency,
                kind = BenchmarkKind.equity,
            ),
        )
        return key
    }

    private fun storeClose(key: String, date: LocalDate, close: String) {
        benchmarkPrices.save(
            BenchmarkPrice(benchmarkKey = key, priceDate = date, close = BigDecimal(close), asOf = Instant.now()),
        )
    }

    private fun seedRate(currency: String, date: LocalDate, rate: String) {
        fxRates.save(
            FxRate(
                provider = "frankfurter",
                baseCurrency = currency,
                quoteCurrency = "EUR",
                rate = BigDecimal(rate),
                rateDate = date,
            ),
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "bm${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
