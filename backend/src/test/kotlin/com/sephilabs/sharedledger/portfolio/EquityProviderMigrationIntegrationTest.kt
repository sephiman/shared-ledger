package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.EquityProviderMigration
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import com.sephilabs.sharedledger.portfolio.price.SymbolCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class EquityProviderMigrationIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val holdingService: HoldingService,
    private val holdings: HoldingRepository,
    private val migration: EquityProviderMigration,
    private val prices: PricePointRepository,
    private val stubEquity: StubEquityProvider,
) : IntegrationTestBase() {

    @Test
    fun `re-resolves old-provider holdings to yahoo symbols and re-backfills`() {
        val (user, household) = seed()
        val isin = "IE0003XJA0J9"
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = "WEBN",
                isin = isin,
                provider = HoldingProvider.eodhd,
                providerSymbol = "WEBN.XETRA",
            ),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = LocalDate.now().minusDays(400), quantity = BigDecimal("10"), unitPrice = BigDecimal("9.5")),
            user,
        )
        stubEquity.isinResults = mapOf(
            isin to listOf(
                SymbolCandidate(
                    provider = "yahoo",
                    providerSymbol = "WEBN.DE",
                    name = "Amundi Prime All Country World UCITS ETF",
                    symbol = "WEBN",
                    currency = "EUR",
                    exchange = "XETRA",
                ),
            ),
        )
        stubEquity.history["WEBN.DE"] = mutableListOf(
            DailyPrice(LocalDate.now().minusDays(400), BigDecimal("9.5")),
            DailyPrice(LocalDate.now().minusDays(1), BigDecimal("11.2")),
        )

        migration.migrate()

        val migrated = holdings.findById(holding.id).orElseThrow()
        assertThat(migrated.provider).isEqualTo(HoldingProvider.yahoo)
        assertThat(migrated.providerSymbol).isEqualTo("WEBN.DE")
        assertThat(migrated.nativeCurrency).isEqualTo("EUR")
        // Backfill re-ran under the new coordinates, uncapped down to the earliest lot.
        assertThat(prices.findMinPriceDate("yahoo", "WEBN.DE", "EUR")).isEqualTo(LocalDate.now().minusDays(400))
    }

    @Test
    fun `picks the unique base-ticker match among multiple listings`() {
        val (user, household) = seed()
        val isin = "US0378331005"
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.stock,
                symbol = "AAPL",
                nativeCurrency = "USD",
                isin = isin,
                provider = HoldingProvider.twelvedata,
                providerSymbol = "AAPL",
            ),
            user,
        )
        stubEquity.isinResults = mapOf(
            isin to listOf(
                SymbolCandidate(provider = "yahoo", providerSymbol = "AAPL", name = "Apple Inc.", symbol = "AAPL", currency = "USD"),
                SymbolCandidate(provider = "yahoo", providerSymbol = "APC.DE", name = "Apple Inc.", symbol = "APC", currency = "EUR"),
            ),
        )

        migration.migrate()

        val migrated = holdings.findById(holding.id).orElseThrow()
        assertThat(migrated.provider).isEqualTo(HoldingProvider.yahoo)
        assertThat(migrated.providerSymbol).isEqualTo("AAPL")
        assertThat(migrated.nativeCurrency).isEqualTo("USD")
    }

    @Test
    fun `ambiguous or missing matches unlink for manual re-search`() {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = "NOMATCH",
                provider = HoldingProvider.eodhd,
                providerSymbol = "NOMATCH.XETRA",
            ),
            user,
        )
        stubEquity.searchResults = emptyList()

        migration.migrate()

        val migrated = holdings.findById(holding.id).orElseThrow()
        assertThat(migrated.provider).isNull()
        assertThat(migrated.providerSymbol).isNull()
    }

    @Test
    fun `provider outage leaves the holding untouched for the next startup`() {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = "RETRY",
                provider = HoldingProvider.eodhd,
                providerSymbol = "RETRY.XETRA",
            ),
            user,
        )
        stubEquity.searchFails = true
        try {
            migration.migrate()
        } finally {
            stubEquity.searchFails = false
        }

        val untouched = holdings.findById(holding.id).orElseThrow()
        assertThat(untouched.provider).isEqualTo(HoldingProvider.eodhd)
        assertThat(untouched.providerSymbol).isEqualTo("RETRY.XETRA")
    }

    @Test
    fun `yahoo-linked and crypto holdings are not touched`() {
        val (user, household) = seed()
        val yahooHolding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = "OKAY",
                provider = HoldingProvider.yahoo,
                providerSymbol = "OKAY.DE",
            ),
            user,
        )
        val cryptoHolding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "BTC",
                provider = HoldingProvider.coingecko,
                providerSymbol = "bitcoin-${System.nanoTime()}",
            ),
            user,
        )

        migration.migrate()

        assertThat(holdings.findById(yahooHolding.id).orElseThrow().providerSymbol).isEqualTo("OKAY.DE")
        assertThat(holdings.findById(cryptoHolding.id).orElseThrow().provider).isEqualTo(HoldingProvider.coingecko)
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "mg${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
