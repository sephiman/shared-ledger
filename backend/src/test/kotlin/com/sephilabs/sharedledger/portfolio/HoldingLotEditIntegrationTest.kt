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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** Editing a lot is a wholesale replace: the holding's entire ledger is replayed with the new values, so an
 *  edit faces exactly the validation a fresh trade would, and a rejected one leaves nothing behind. */
class HoldingLotEditIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: HoldingService,
    private val valuation: PortfolioValuationService,
    private val fxRates: FxRateRepository,
) : IntegrationTestBase() {

    @Test
    fun `an edit that would uncover a later sale is rejected and changes nothing`() {
        val (user, household) = seed()
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "EDT"),
            user,
        )
        val buy = service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = LocalDate.of(2026, 1, 10), quantity = BigDecimal("2"), unitPrice = BigDecimal("100")),
            user,
        )
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

        // Shrinking the covering BUY below what was later sold.
        val failure = runCatching {
            service.updateLot(
                household.id, holding.id, buy.id,
                LotRequest(tradedOn = buy.tradedOn, quantity = BigDecimal("0.5"), unitPrice = BigDecimal("100")),
                user,
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(AppException::class.java)
        assertThat((failure as AppException).code).isEqualTo("LOT_SELL_EXCEEDS_HOLDINGS")
        // Names the sale it collides with, so the message can point at a date rather than "some sale".
        assertThat(failure.args).containsExactly(sale.tradedOn.toString())

        // The new values are flushed before the replay rejects them, so this asserts the rollback rather
        // than the service merely returning early.
        val detail = service.get(household.id, holding.id)
        assertThat(detail.lots.single { it.id == buy.id }.quantity).isEqualByComparingTo(BigDecimal("2"))
        assertThat(detail.netQuantity).isEqualByComparingTo(BigDecimal("1"))
        assertThat(detail.remainingCostBasis).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(detail.realizedPnl).isEqualByComparingTo(BigDecimal("50.00"))
    }

    @Test
    fun `moving the trade date re-freezes the fx rate and moves the money-weighted flow`() {
        val (user, household) = seed()
        // DKK is used nowhere else in the suite: fx_rates is keyed globally and the schema resets once per
        // run, so a shared currency would read another test's rows.
        seedFxRate("DKK", LocalDate.of(2026, 3, 1), "0.10000000")
        seedFxRate("DKK", LocalDate.of(2026, 6, 1), "0.20000000")
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.stock, symbol = "NOVO", nativeCurrency = "DKK"),
            user,
        )
        val lot = service.addLot(
            household.id, holding.id,
            LotRequest(
                tradedOn = LocalDate.of(2026, 3, 1),
                quantity = BigDecimal("10"),
                unitPrice = BigDecimal("50"),
                currency = "DKK",
            ),
            user,
        )
        assertThat(lot.fxRateToBase).isEqualByComparingTo(BigDecimal("0.10"))
        // (10 × 50) × 0.10
        assertThat(lot.amountBase).isEqualByComparingTo(BigDecimal("50.00"))
        assertThat(valuation.summary(household.id).moneyWeightedReturn.from).isEqualTo(LocalDate.of(2026, 3, 1))

        val moved = service.updateLot(
            household.id, holding.id, lot.id,
            LotRequest(
                tradedOn = LocalDate.of(2026, 6, 1),
                quantity = BigDecimal("10"),
                unitPrice = BigDecimal("50"),
                currency = "DKK",
            ),
            user,
        )
        // The frozen-at-trade-time rule follows the new date instead of pinning the rate it was created with.
        assertThat(moved.fxRateToBase).isEqualByComparingTo(BigDecimal("0.20"))
        assertThat(moved.amountBase).isEqualByComparingTo(BigDecimal("100.00"))
        // ...and the XIRR flow moves with it.
        assertThat(valuation.summary(household.id).moneyWeightedReturn.from).isEqualTo(LocalDate.of(2026, 6, 1))
    }

    @Test
    fun `a buy becomes a sale when the history covers it, and realized P&L follows`() {
        val (user, household) = seed()
        val holding = service.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "CNV"),
            user,
        )
        service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = LocalDate.of(2026, 1, 1), quantity = BigDecimal("10"), unitPrice = BigDecimal("100")),
            user,
        )
        val second = service.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = LocalDate.of(2026, 2, 1), quantity = BigDecimal("5"), unitPrice = BigDecimal("120")),
            user,
        )
        assertThat(service.get(household.id, holding.id).netQuantity).isEqualByComparingTo(BigDecimal("15"))

        val converted = service.updateLot(
            household.id, holding.id, second.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = LocalDate.of(2026, 2, 1),
                quantity = BigDecimal("5"),
                unitPrice = BigDecimal("120"),
            ),
            user,
        )
        assertThat(converted.type).isEqualTo(LotType.SELL)
        // Proceeds now, not cost: 5 × 120.
        assertThat(converted.amountBase).isEqualByComparingTo(BigDecimal("600.00"))

        val detail = service.get(household.id, holding.id)
        assertThat(detail.netQuantity).isEqualByComparingTo(BigDecimal("5"))
        // FIFO: the sold units came out of the 100-a-unit buy, leaving 5 of it at cost.
        assertThat(detail.remainingCostBasis).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(detail.realizedPnl).isEqualByComparingTo(BigDecimal("100.00"))
    }

    private fun seedFxRate(currency: String, date: LocalDate, rate: String) {
        fxRates.save(
            FxRate(
                provider = "frankfurter",
                baseCurrency = currency,
                quoteCurrency = "EUR",
                rate = BigDecimal(rate),
                rateDate = date,
            )
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "le${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
