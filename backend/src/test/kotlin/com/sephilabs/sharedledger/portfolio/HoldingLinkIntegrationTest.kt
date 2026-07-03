package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.LocalDate

@Import(StubPriceProviderConfig::class)
class HoldingLinkIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: HoldingService,
    private val prices: PricePointRepository,
    private val stubCrypto: StubCryptoProvider,
) : IntegrationTestBase() {

    @Test
    fun `linking backfills from the earliest lot clamped to the crypto ceiling`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "clamp-coin-${System.nanoTime()}"
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "CLMP"),
            user,
        )
        // Lot far beyond the 365-day Demo-plan ceiling.
        service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(500), quantity = BigDecimal("1"), unitPrice = BigDecimal("10000")),
            user,
        )
        stubCrypto.history[coinId] = mutableListOf(DailyPrice(today.minusDays(10), BigDecimal("50000")))

        val linked = service.link(
            household.id, holding.id,
            LinkRequest(provider = HoldingProvider.coingecko, providerSymbol = coinId),
            user,
        )

        assertThat(linked.linked).isTrue()
        val call = stubCrypto.historyCalls.single { it.first == coinId }
        assertThat(call.second).isEqualTo(today.minusDays(365))
        assertThat(call.third).isEqualTo(today)
        assertThat(prices.findMaxPriceDate("coingecko", coinId, "EUR")).isEqualTo(today.minusDays(10))
    }

    @Test
    fun `adding an older lot extends the backfill head range only`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "extend-coin-${System.nanoTime()}"
        stubCrypto.history[coinId] = mutableListOf(
            DailyPrice(today.minusDays(60), BigDecimal("90")),
            DailyPrice(today.minusDays(30), BigDecimal("100")),
            DailyPrice(today.minusDays(1), BigDecimal("110")),
        )
        val holding = service.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "EXT",
                provider = HoldingProvider.coingecko,
                providerSymbol = coinId,
            ),
            user,
        )
        service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(30), quantity = BigDecimal("1"), unitPrice = BigDecimal("100")),
            user,
        )
        stubCrypto.historyCalls.clear()

        // Older lot: only the missing head range [today-60, minStored-1] should be fetched.
        service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(60), quantity = BigDecimal("1"), unitPrice = BigDecimal("90")),
            user,
        )

        val minStored = prices.findMinPriceDate("coingecko", coinId, "EUR")
        assertThat(minStored).isEqualTo(today.minusDays(60))
        val call = stubCrypto.historyCalls.single { it.first == coinId }
        assertThat(call.second).isEqualTo(today.minusDays(60))
        assertThat(call.third).isBefore(today.minusDays(30).plusDays(1))
    }

    @Test
    fun `unlink keeps stored prices and clears provider coordinates`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val coinId = "unlink-coin-${System.nanoTime()}"
        stubCrypto.history[coinId] = mutableListOf(DailyPrice(today.minusDays(1), BigDecimal("100")))
        val holding = service.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "UNLK",
                provider = HoldingProvider.coingecko,
                providerSymbol = coinId,
            ),
            user,
        )
        service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = today.minusDays(1), quantity = BigDecimal("1"), unitPrice = BigDecimal("100")),
            user,
        )

        val unlinked = service.unlink(household.id, holding.id, user)

        assertThat(unlinked.linked).isFalse()
        assertThat(unlinked.provider).isNull()
        assertThat(prices.findMaxPriceDate("coingecko", coinId, "EUR")).isNotNull()

        assertThatThrownBy { service.unlink(household.id, holding.id, user) }
            .isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "HOLDING_NOT_LINKED")
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "lk${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
