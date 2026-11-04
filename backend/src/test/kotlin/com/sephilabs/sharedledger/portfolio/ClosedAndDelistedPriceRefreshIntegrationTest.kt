package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import com.sephilabs.sharedledger.portfolio.price.PriceRefreshService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ResourceLock("stub-price-providers")
class ClosedAndDelistedPriceRefreshIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val holdingService: HoldingService,
    private val refresh: PriceRefreshService,
    private val prices: PricePointRepository,
    private val stubEquity: StubEquityProvider,
    private val stubCrypto: StubCryptoProvider,
) : IntegrationTestBase() {

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `closed holding whose history reaches the sell date makes no provider call`() {
        val etf = etf(prices = listOf(day(20, "50"), day(10, "52")))
        buy(etf, daysAgo = 20, qty = "5")
        sell(etf, daysAgo = 10, qty = "5")
        stubEquity.historyCalls.clear()

        refresh.refreshEquities(today)

        assertThat(callsFor(etf)).isEmpty()
    }

    @Test
    fun `closed holding with a gap before the sell date is filled only up to a week past the close`() {
        val etf = etf(prices = listOf(day(20, "50")))
        buy(etf, daysAgo = 20, qty = "5")
        sell(etf, daysAgo = 10, qty = "5")
        stubEquity.history.getValue(etf.ps).addAll(listOf(day(15, "51"), day(10, "52"), day(5, "53"), day(1, "54")))
        stubEquity.historyCalls.clear()

        refresh.refreshEquities(today)

        assertThat(callsFor(etf)).containsExactly(Triple(etf.ps, ago(19), ago(3)))
        assertThat(storedDates(etf)).containsExactly(ago(20), ago(15), ago(10), ago(5))

        stubEquity.historyCalls.clear()
        refresh.refreshEquities(today)
        assertThat(callsFor(etf)).isEmpty()
    }

    @Test
    fun `closed holding still gets a missing head filled by the nightly run`() {
        val etf = etf(prices = emptyList())
        buy(etf, daysAgo = 20, qty = "5")
        seedPrice(etf.ps, ago(10), "52")
        sell(etf, daysAgo = 10, qty = "5")
        stubEquity.history.getValue(etf.ps).addAll(listOf(day(20, "50"), day(15, "51")))
        stubEquity.historyCalls.clear()

        refresh.refreshEquities(today)

        assertThat(callsFor(etf)).containsExactly(Triple(etf.ps, ago(27), ago(11)))
        assertThat(storedDates(etf)).containsExactly(ago(20), ago(15), ago(10))
    }

    @Test
    fun `a coordinate closed in one household but open in another is refreshed to today`() {
        val closed = etf(prices = listOf(day(20, "50"), day(10, "52")))
        buy(closed, daysAgo = 20, qty = "5")
        sell(closed, daysAgo = 10, qty = "5")
        val open = etf(prices = emptyList(), providerSymbol = closed.ps)
        buy(open, daysAgo = 10, qty = "1")
        stubEquity.history.getValue(closed.ps).add(day(0, "55"))
        stubEquity.historyCalls.clear()

        refresh.refreshEquities(today)

        assertThat(prices.findMaxPriceDate("yahoo", closed.ps, "EUR")).isEqualTo(today)
    }

    @Test
    fun `a 404 flags the holding, keeps its stored history, backs off a week and clears once listed again`() {
        val etf = etf(prices = listOf(day(10, "50"), day(5, "51")))
        buy(etf, daysAgo = 10, qty = "5")
        val storedBefore = storedDates(etf)
        stubEquity.notFoundFor += etf.ps

        refresh.refreshEquities(today)

        assertThat(notFoundSince(etf)).isEqualTo(today)
        assertThat(storedDates(etf)).isEqualTo(storedBefore)

        stubEquity.historyCalls.clear()
        refresh.refreshEquities(today.plusDays(1))
        refresh.refreshEquities(today.plusDays(6))
        assertThat(callsFor(etf)).isEmpty()

        refresh.refreshEquities(today.plusDays(7))
        assertThat(callsFor(etf)).hasSize(1)
        assertThat(notFoundSince(etf)).isEqualTo(today)

        stubEquity.notFoundFor -= etf.ps
        stubEquity.history.getValue(etf.ps).add(DailyPrice(today.plusDays(14), BigDecimal("60")))
        refresh.refreshEquities(today.plusDays(14))

        assertThat(notFoundSince(etf)).isNull()
        assertThat(prices.findMaxPriceDate("yahoo", etf.ps, "EUR")).isEqualTo(today.plusDays(14))
        assertThat(storedDates(etf)).containsAll(storedBefore)
    }

    @Test
    fun `a closed holding delisted before its history reached the sell date is flagged, not retried nightly`() {
        val etf = etf(prices = listOf(day(20, "50")))
        buy(etf, daysAgo = 20, qty = "5")
        sell(etf, daysAgo = 10, qty = "5")
        stubEquity.notFoundFor += etf.ps

        refresh.refreshEquities(today)
        stubEquity.historyCalls.clear()
        refresh.refreshEquities(today.plusDays(1))

        assertThat(notFoundSince(etf)).isEqualTo(today)
        assertThat(callsFor(etf)).isEmpty()
        assertThat(storedDates(etf)).containsExactly(ago(20))
    }

    @Test
    fun `linking a symbol the provider does not list flags it, and relinking clears the flag`() {
        val (user, household) = seed()
        val missing = "GONE${System.nanoTime() % 100000}.MI"
        stubEquity.notFoundFor += missing
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = "GONE${System.nanoTime() % 100000}",
                provider = HoldingProvider.yahoo,
                providerSymbol = missing,
            ),
            user,
        )
        assertThat(holdingService.get(household.id, holding.id).providerNotFoundSince).isEqualTo(today)

        val relinked = holdingService.link(
            household.id, holding.id, LinkRequest(HoldingProvider.yahoo, "OK${System.nanoTime() % 100000}.DE"), user,
        )

        assertThat(relinked.providerNotFoundSince).isNull()
        assertThat(holdingService.get(household.id, holding.id).providerNotFoundSince).isNull()
    }

    @Test
    fun `reopening with a new buy gap-fills only the tail from the last stored day to today`() {
        val etf = closedEtfWithFutureData()

        buy(etf, daysAgo = 2, qty = "2")

        assertThat(callsFor(etf)).containsExactly(Triple(etf.ps, ago(10), today))
        assertThat(storedDates(etf)).containsExactly(ago(20), ago(10), ago(5), today)
    }

    @Test
    fun `reopening by deleting the closing sell gap-fills the tail`() {
        val etf = closedEtfWithFutureData()
        val sellId = holdingService.get(etf.household, etf.id).lots.single { it.type == LotType.SELL }.id

        holdingService.deleteLot(etf.household, etf.id, sellId, etf.user)

        assertThat(callsFor(etf)).containsExactly(Triple(etf.ps, ago(10), today))
        assertThat(prices.findMaxPriceDate("yahoo", etf.ps, "EUR")).isEqualTo(today)
    }

    @Test
    fun `reopening by editing the closing sell to a smaller quantity gap-fills the tail`() {
        val etf = closedEtfWithFutureData()
        val sellId = holdingService.get(etf.household, etf.id).lots.single { it.type == LotType.SELL }.id

        holdingService.updateLot(
            etf.household, etf.id, sellId,
            LotRequest(type = LotType.SELL, tradedOn = ago(10), quantity = BigDecimal("3"), unitPrice = BigDecimal("52")),
            etf.user,
        )

        assertThat(callsFor(etf)).containsExactly(Triple(etf.ps, ago(10), today))
        assertThat(prices.findMaxPriceDate("yahoo", etf.ps, "EUR")).isEqualTo(today)
    }

    @Test
    fun `reopening with a buy older than every lot fills both the new head and the tail`() {
        val etf = closedEtfWithFutureData()
        stubEquity.history.getValue(etf.ps).add(day(30, "45"))

        buy(etf, daysAgo = 30, qty = "1")

        assertThat(callsFor(etf)).containsExactlyInAnyOrder(
            Triple(etf.ps, ago(37), ago(21)),
            Triple(etf.ps, ago(10), today),
        )
        assertThat(storedDates(etf)).containsExactly(ago(30), ago(20), ago(10), ago(5), today)
    }

    @Test
    fun `a closed crypto holding gets no current price and no gap-fill once its history reaches the close`() {
        val (user, household) = seed()
        val coinId = "closed-coin-${System.nanoTime()}"
        stubCrypto.history[coinId] = mutableListOf(day(10, "100"), day(5, "110"))
        stubCrypto.current[coinId] = BigDecimal("120")
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "CLS${System.nanoTime() % 1000}",
                provider = HoldingProvider.coingecko,
                providerSymbol = coinId,
            ),
            user,
        )
        holdingService.addLot(household.id, holding.id, lot(LotType.BUY, 10, "1"), user)
        holdingService.addLot(household.id, holding.id, lot(LotType.SELL, 5, "1"), user)
        stubCrypto.historyCalls.clear()

        refresh.refreshCrypto(today)

        assertThat(stubCrypto.historyCalls.filter { it.first == coinId }).isEmpty()
        assertThat(prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", coinId, "EUR", today)).isNull()
        assertThat(prices.findMaxPriceDate("coingecko", coinId, "EUR")).isEqualTo(ago(5))
    }

    private data class Etf(val id: UUID, val household: UUID, val user: User, val ps: String)

    /** Closed (buy -20, sell -10) with history at both dates, and the provider already serving -5 and today. */
    private fun closedEtfWithFutureData(): Etf {
        val etf = etf(prices = listOf(day(20, "50"), day(10, "52")))
        buy(etf, daysAgo = 20, qty = "5")
        sell(etf, daysAgo = 10, qty = "5")
        stubEquity.history.getValue(etf.ps).addAll(listOf(day(5, "53"), day(0, "56")))
        stubEquity.historyCalls.clear()
        return etf
    }

    private fun etf(prices: List<DailyPrice>, providerSymbol: String? = null): Etf {
        val (user, household) = seed()
        val symbol = "CL${System.nanoTime() % 1_000_000}"
        val ps = providerSymbol ?: "$symbol.MI"
        if (providerSymbol == null) stubEquity.history[ps] = prices.toMutableList()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = symbol,
                provider = HoldingProvider.yahoo,
                providerSymbol = ps,
            ),
            user,
        )
        return Etf(holding.id, household.id, user, ps)
    }

    private fun buy(etf: Etf, daysAgo: Long, qty: String) =
        holdingService.addLot(etf.household, etf.id, lot(LotType.BUY, daysAgo, qty), etf.user)

    private fun sell(etf: Etf, daysAgo: Long, qty: String) =
        holdingService.addLot(etf.household, etf.id, lot(LotType.SELL, daysAgo, qty), etf.user)

    private fun lot(type: LotType, daysAgo: Long, qty: String) =
        LotRequest(type = type, tradedOn = ago(daysAgo), quantity = BigDecimal(qty), unitPrice = BigDecimal("50"))

    private fun ago(days: Long): LocalDate = today.minusDays(days)

    private fun day(daysAgo: Long, price: String) = DailyPrice(ago(daysAgo), BigDecimal(price))

    private fun callsFor(etf: Etf) = stubEquity.historyCalls.filter { it.first == etf.ps }

    private fun notFoundSince(etf: Etf): LocalDate? = holdingService.get(etf.household, etf.id).providerNotFoundSince

    private fun storedDates(etf: Etf): List<LocalDate> =
        prices.findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
            "yahoo", etf.ps, "EUR", today.minusYears(1), today.plusYears(1),
        ).map { it.priceDate }

    private fun seedPrice(providerSymbol: String, date: LocalDate, price: String) {
        prices.save(
            PricePoint(
                provider = "yahoo",
                providerSymbol = providerSymbol,
                currency = "EUR",
                price = BigDecimal(price),
                priceDate = date,
                asOf = Instant.now(),
            )
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "cd${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
