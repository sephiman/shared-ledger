package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.SymbolCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

class PortfolioImportIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val importService: PortfolioImportService,
    private val holdingService: HoldingService,
    private val stubEquity: StubEquityProvider,
    private val stubFx: StubFxProvider,
) : IntegrationTestBase() {

    // Old-style header without provider columns: still accepted (backward compatible).
    private val header = "type;asset_class;symbol;label;native_currency;isin;traded_on;quantity;unit_price;cost_currency;fee;note"

    // What export writes today: provider coordinates included for round-trip fidelity.
    private val exportHeader =
        "type;asset_class;symbol;label;native_currency;isin;provider;provider_symbol;traded_on;quantity;unit_price;cost_currency;fee;note"

    @Test
    fun `preview validates rows and reports errors without writing`() {
        val (_, household) = seed()
        val csv = """
            $header
            BUY;crypto;BTC;Bitcoin;EUR;;2026-01-10;0,5;50000;EUR;10;
            BUY;wrong;BTC;;EUR;;2026-01-10;1;100;EUR;;
            BUY;crypto;ETH;;EUR;;not-a-date;1;100;EUR;;
            BUY;crypto;SOL;;EUR;;2026-01-10;-1;100;EUR;;
        """.trimIndent()

        val preview = importService.preview(household.id, stream(csv))

        assertThat(preview.totalRows).isEqualTo(4)
        assertThat(preview.wouldInsert).isEqualTo(1)
        assertThat(preview.errorCount).isEqualTo(3)
        assertThat(preview.errors.map { it.code }).containsExactlyInAnyOrder(
            "IMPORT_ASSET_CLASS_INVALID", "IMPORT_DATE_INVALID", "IMPORT_QUANTITY_INVALID",
        )
        // Cost basis of the valid row: 0.5 × 50000 + 10.
        assertThat(preview.sumAssets).isEqualByComparingTo(BigDecimal("25010.00"))
        assertThat(holdingService.list(household.id)).isEmpty()
    }

    @Test
    fun `execute groups rows into holdings and dedupes against DB and in-file`() {
        val (user, household) = seed()
        val csv = """
            $header
            BUY;crypto;BTC;Bitcoin;EUR;;2026-01-10;0,5;50000;EUR;10;
            BUY;crypto;BTC;Bitcoin;EUR;;2026-02-10;0,25;55000;EUR;;DCA batch 2
            BUY;crypto;BTC;Bitcoin;EUR;;2026-01-10;0,5;50000;EUR;10;
        """.trimIndent()

        val result = importService.execute(household.id, stream(csv), user)

        assertThat(result.inserted).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(1)
        val holding = holdingService.list(household.id).single()
        assertThat(holding.symbol).isEqualTo("BTC")
        assertThat(holding.lots).hasSize(2)
        assertThat(holding.netQuantity).isEqualByComparingTo(BigDecimal("0.75"))
        assertThat(holding.lots.single { it.tradedOn == java.time.LocalDate.of(2026, 2, 10) }.note)
            .isEqualTo("DCA batch 2")

        // Re-importing the same file skips everything (round-trip idempotence).
        val second = importService.execute(household.id, stream(csv), user)
        assertThat(second.inserted).isZero()
        assertThat(second.skipped).isEqualTo(3)
        assertThat(holdingService.list(household.id).single().lots).hasSize(2)
    }

    @Test
    fun `export and re-import round-trips with all rows skipped`() {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.etf, symbol = "WEBN", label = "All Country"),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(
                tradedOn = java.time.LocalDate.of(2026, 3, 1),
                quantity = BigDecimal("100"),
                unitPrice = BigDecimal("9.876"),
                fee = BigDecimal("1.5"),
            ),
            user,
        )

        val exported = holdingService.exportCsv(household.id)
        assertThat(exported.lineSequence().first())
            .isEqualTo(exportHeader)

        val result = importService.execute(household.id, stream(exported), user)
        assertThat(result.inserted).isZero()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `explicit provider columns link the holding and round-trip through export`() {
        val (user, household) = seed()
        val csv = """
            $exportHeader
            BUY;crypto;BTC;Bitcoin;EUR;;coingecko;bitcoin;2026-01-10;0,5;50000;EUR;10;
        """.trimIndent()

        importService.execute(household.id, stream(csv), user)

        val holding = holdingService.list(household.id).single()
        assertThat(holding.linked).isTrue()
        assertThat(holding.provider).isEqualTo(HoldingProvider.coingecko)
        assertThat(holding.providerSymbol).isEqualTo("bitcoin")

        // Export carries the link back out, and re-importing it skips the lot (no dupes).
        val exported = holdingService.exportCsv(household.id)
        assertThat(exported).contains("coingecko;bitcoin")
        val round = importService.execute(household.id, stream(exported), user)
        assertThat(round.inserted).isZero()
        assertThat(round.skipped).isEqualTo(1)
    }

    @Test
    fun `an invalid or half-specified provider is a row error`() {
        val (_, household) = seed()
        val csv = """
            $exportHeader
            BUY;crypto;BTC;Bitcoin;EUR;;notaprovider;bitcoin;2026-01-10;1;50000;EUR;;
            BUY;crypto;ETH;;EUR;;coingecko;;2026-01-11;1;3000;EUR;;
        """.trimIndent()

        val preview = importService.preview(household.id, stream(csv))

        assertThat(preview.errors.map { it.code }).contains("IMPORT_PROVIDER_INVALID", "IMPORT_PROVIDER_INCOMPLETE")
    }

    @Test
    fun `equity holdings with a unique ISIN match auto-link on import`() {
        val (user, household) = seed()
        val isin = "IE000716YHJ7"
        stubEquity.isinResults = mapOf(
            isin to listOf(
                SymbolCandidate(
                    provider = "yahoo",
                    providerSymbol = "WEBN.DE",
                    name = "Amundi Prime All Country World",
                    symbol = "WEBN",
                    currency = "EUR",
                    exchange = "XETRA",
                ),
            ),
        )
        val csv = """
            $header
            BUY;etf;WEBN;All Country;EUR;$isin;2026-03-01;10;9,87;EUR;;
        """.trimIndent()

        importService.execute(household.id, stream(csv), user)

        val holding = holdingService.list(household.id).single()
        assertThat(holding.linked).isTrue()
        assertThat(holding.provider).isEqualTo(HoldingProvider.yahoo)
        assertThat(holding.providerSymbol).isEqualTo("WEBN.DE")
        assertThat(holding.isin).isEqualTo(isin)
    }

    @Test
    fun `ambiguous or missing ISIN matches leave the holding unlinked`() {
        val (user, household) = seed()
        val isin = "US0378331005"
        stubEquity.isinResults = mapOf(
            isin to listOf(
                SymbolCandidate(provider = "eodhd", providerSymbol = "AAPL.US", name = "Apple", currency = "USD"),
                SymbolCandidate(provider = "eodhd", providerSymbol = "AAPL.MX", name = "Apple 2", currency = "USD"),
            ),
        )
        // Seed a USD rate so the lot itself imports.
        stubFxSeed()
        val csv = """
            $header
            BUY;stock;AAPL;Apple;USD;$isin;2026-03-02;5;180;USD;;
        """.trimIndent()

        importService.execute(household.id, stream(csv), user)

        val holding = holdingService.list(household.id).single()
        assertThat(holding.linked).isFalse()
    }

    @Test
    fun `missing fx rate for a foreign-currency lot is a row error at preview`() {
        val (_, household) = seed()
        val csv = """
            $header
            BUY;stock;MSFT;Microsoft;GBP;;2026-03-02;5;300;GBP;;
        """.trimIndent()

        val preview = importService.preview(household.id, stream(csv))

        assertThat(preview.errorCount).isEqualTo(1)
        assertThat(preview.errors.single().code).isEqualTo("LOT_FX_RATE_UNAVAILABLE")
    }

    @Test
    fun `sell rows import in ledger order and update the net quantity`() {
        val (user, household) = seed()
        // The SELL is listed before its covering BUY; the importer must insert in ledger order.
        val csv = """
            $header
            SELL;crypto;BTC;Bitcoin;EUR;;2026-02-10;0,4;60000;EUR;;
            BUY;crypto;BTC;Bitcoin;EUR;;2026-01-10;1;50000;EUR;;
        """.trimIndent()

        val result = importService.execute(household.id, stream(csv), user)

        assertThat(result.inserted).isEqualTo(2)
        val holding = holdingService.list(household.id).single()
        assertThat(holding.netQuantity).isEqualByComparingTo(BigDecimal("0.6"))
        // 0.4 × 60000 − 0.4 × 50000
        assertThat(holding.realizedPnl).isEqualByComparingTo(BigDecimal("4000.00"))

        // Round-trip: exporting and re-importing skips both movements.
        val second = importService.execute(household.id, stream(holdingService.exportCsv(household.id)), user)
        assertThat(second.inserted).isZero()
        assertThat(second.skipped).isEqualTo(2)
    }

    @Test
    fun `an oversell is a row error at preview and blocks execute`() {
        val (user, household) = seed()
        val csv = """
            $header
            BUY;crypto;BTC;Bitcoin;EUR;;2026-01-10;1;50000;EUR;;
            SELL;crypto;BTC;Bitcoin;EUR;;2026-02-10;2;60000;EUR;;
        """.trimIndent()

        val preview = importService.preview(household.id, stream(csv))
        assertThat(preview.errorCount).isEqualTo(1)
        assertThat(preview.errors.single().code).isEqualTo("LOT_SELL_EXCEEDS_HOLDINGS")
        assertThat(preview.errors.single().row).isEqualTo(3)

        org.assertj.core.api.Assertions.assertThatThrownBy { importService.execute(household.id, stream(csv), user) }
            .hasFieldOrPropertyWithValue("code", "IMPORT_VALIDATION_FAILED")
        assertThat(holdingService.list(household.id)).isEmpty()
    }

    @Test
    fun `an invalid type is a row error`() {
        val (_, household) = seed()
        val csv = """
            $header
            HOLD;crypto;BTC;Bitcoin;EUR;;2026-01-10;1;50000;EUR;;
        """.trimIndent()

        val preview = importService.preview(household.id, stream(csv))
        assertThat(preview.errors.single().code).isEqualTo("IMPORT_TYPE_INVALID")
    }

    private fun stubFxSeed() {
        // The on-demand FX fetch goes through StubFxProvider; give it a USD observation
        // just before the imported lot date so the frozen rate resolves.
        stubFx.history["USD"] = mutableListOf(
            com.sephilabs.sharedledger.portfolio.price.DailyPrice(java.time.LocalDate.of(2026, 3, 1), BigDecimal("0.92")),
        )
    }

    private fun stream(csv: String) = ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8))

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "pi${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
