package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.FxRate
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.LocalDate

@Import(StubPriceProviderConfig::class)
class HoldingLifecycleIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: HoldingService,
    private val fxRates: FxRateRepository,
) : IntegrationTestBase() {

    @Test
    fun `create holding, add lots, edit and soft delete`() {
        val (user, household) = seed()

        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = " btc ", label = "Bitcoin"),
            user,
        )
        assertThat(holding.symbol).isEqualTo("BTC")
        assertThat(holding.nativeCurrency).isEqualTo("EUR")
        assertThat(holding.linked).isFalse()

        assertThatThrownBy {
            service.create(household.id, HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "BTC"), user)
        }.isInstanceOf(AppException::class.java).hasFieldOrPropertyWithValue("code", "HOLDING_DUPLICATE_SYMBOL")

        val lot = service.addLot(
            household.id, holding.id,
            LotRequest(
                tradedOn = LocalDate.of(2025, 3, 1),
                quantity = BigDecimal("0.500000000000"),
                unitPrice = BigDecimal("60000"),
                fee = BigDecimal("10"),
            ),
            user,
        )
        assertThat(lot.currency).isEqualTo("EUR")
        assertThat(lot.fxRateToBase).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(lot.amountBase).isEqualByComparingTo(BigDecimal("30010.00"))

        val updatedLot = service.updateLot(
            household.id, holding.id, lot.id,
            LotRequest(
                tradedOn = LocalDate.of(2025, 3, 1),
                quantity = BigDecimal("0.250000000000"),
                unitPrice = BigDecimal("60000"),
                fee = null,
            ),
            user,
        )
        assertThat(updatedLot.amountBase).isEqualByComparingTo(BigDecimal("15000.00"))

        val detail = service.get(household.id, holding.id)
        assertThat(detail.netQuantity).isEqualByComparingTo(BigDecimal("0.25"))
        assertThat(detail.remainingCostBasis).isEqualByComparingTo(BigDecimal("15000.00"))

        service.deleteLot(household.id, holding.id, lot.id, user)
        assertThat(service.get(household.id, holding.id).lots).isEmpty()

        service.delete(household.id, holding.id, user)
        assertThatThrownBy { service.get(household.id, holding.id) }
            .isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "HOLDING_NOT_FOUND")
    }

    @Test
    fun `non-EUR lot freezes the last known fx rate and fails without one`() {
        val (user, household) = seed()
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.stock, symbol = "AAPL", nativeCurrency = "USD"),
            user,
        )

        assertThatThrownBy {
            service.addLot(
                household.id, holding.id,
                LotRequest(
                    tradedOn = LocalDate.of(2025, 5, 10),
                    quantity = BigDecimal("10"),
                    unitPrice = BigDecimal("190"),
                    currency = "USD",
                ),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasFieldOrPropertyWithValue("code", "LOT_FX_RATE_UNAVAILABLE")

        // Friday observation; the Saturday lot must forward-fill from it.
        fxRates.save(
            FxRate(
                provider = "frankfurter",
                baseCurrency = "USD",
                quoteCurrency = "EUR",
                rate = BigDecimal("0.92000000"),
                rateDate = LocalDate.of(2025, 5, 9),
            )
        )
        val lot = service.addLot(
            household.id, holding.id,
            LotRequest(
                tradedOn = LocalDate.of(2025, 5, 10),
                quantity = BigDecimal("10"),
                unitPrice = BigDecimal("190"),
                currency = "USD",
            ),
            user,
        )
        assertThat(lot.fxRateToBase).isEqualByComparingTo(BigDecimal("0.92"))
        // (10 × 190) × 0.92
        assertThat(lot.amountBase).isEqualByComparingTo(BigDecimal("1748.00"))
    }

    @Test
    fun `holdings are scoped to their household`() {
        val (user, household) = seed()
        val (_, otherHousehold) = seed()
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.etf, symbol = "WEBN"),
            user,
        )
        assertThatThrownBy { service.get(otherHousehold.id, holding.id) }
            .isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "HOLDING_NOT_FOUND")
    }

    @Test
    fun `native currency is locked while linked`() {
        val (user, household) = seed()
        val holding = service.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "ETH",
                provider = HoldingProvider.coingecko,
                providerSymbol = "ethereum",
            ),
            user,
        )
        assertThat(holding.linked).isTrue()
        assertThatThrownBy {
            service.update(household.id, holding.id, HoldingUpdateRequest(nativeCurrency = "USD"), user)
        }.isInstanceOf(AppException::class.java).hasFieldOrPropertyWithValue("code", "HOLDING_ALREADY_LINKED")

        val relabeled = service.update(household.id, holding.id, HoldingUpdateRequest(label = "Ethereum"), user)
        assertThat(relabeled.label).isEqualTo("Ethereum")
    }

    @Test
    fun `sales are validated against the replayed ledger`() {
        val (user, household) = seed()
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "SLL"),
            user,
        )
        val buy = service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = LocalDate.of(2026, 1, 10), quantity = BigDecimal("2"), unitPrice = BigDecimal("100")),
            user,
        )

        // Selling more than held is rejected.
        assertThatThrownBy {
            service.addLot(
                household.id, holding.id,
                LotRequest(
                    type = LotType.SELL,
                    tradedOn = LocalDate.of(2026, 2, 1),
                    quantity = BigDecimal("3"),
                    unitPrice = BigDecimal("150"),
                ),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasFieldOrPropertyWithValue("code", "LOT_SELL_EXCEEDS_HOLDINGS")

        val sale = service.addLot(
            household.id, holding.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = LocalDate.of(2026, 2, 1),
                quantity = BigDecimal("1"),
                unitPrice = BigDecimal("150"),
            ),
            user,
        )
        // Proceeds of the sale: 1 × 150.
        assertThat(sale.amountBase).isEqualByComparingTo(BigDecimal("150.00"))

        val detail = service.get(household.id, holding.id)
        assertThat(detail.netQuantity).isEqualByComparingTo(BigDecimal("1"))
        assertThat(detail.remainingCostBasis).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(detail.realizedPnl).isEqualByComparingTo(BigDecimal("50.00"))

        // Removing the covering BUY would leave the SELL uncovered.
        assertThatThrownBy { service.deleteLot(household.id, holding.id, buy.id, user) }
            .isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "LOT_SELL_EXCEEDS_HOLDINGS")

        // Deleting the SELL first, then the BUY, is fine.
        service.deleteLot(household.id, holding.id, sale.id, user)
        service.deleteLot(household.id, holding.id, buy.id, user)
        assertThat(service.get(household.id, holding.id).lots).isEmpty()
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "hl${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
