package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.AssetValueInput
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRequest
import com.sephilabs.sharedledger.networth.snapshot.SnapshotService
import com.sephilabs.sharedledger.networth.snapshot.VALUE_SOURCE_COMPUTED
import com.sephilabs.sharedledger.networth.snapshot.VALUE_SOURCE_OVERRIDDEN
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Import(StubPriceProviderConfig::class)
class SnapshotPortfolioIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val holdingService: HoldingService,
    private val snapshotService: SnapshotService,
    private val prices: PricePointRepository,
    private val valuations: HoldingValuationRepository,
) : IntegrationTestBase() {

    private val date = LocalDate.of(2026, 6, 1)

    @Test
    fun `explicit computed uses the server value and freezes holding valuations`() {
        val (user, household) = seed()
        val holding = seedPricedBtc(household, user, price = "60000", quantity = "0.5")

        val dto = snapshotService.create(
            household.id,
            request(
                // Client sends a drifted number; the server recomputes: 0.5 × 60000 = 30000.
                assets = fullAssetSet("crypto" to input("29999.99", VALUE_SOURCE_COMPUTED)),
            ),
            user,
        )

        val crypto = dto.assets.single { it.assetClassCode == "crypto" }
        assertThat(crypto.value).isEqualByComparingTo(BigDecimal("30000.00"))
        assertThat(crypto.valueSource).isEqualTo(VALUE_SOURCE_COMPUTED)
        assertThat(dto.assets.single { it.assetClassCode == "cash" }.valueSource).isEqualTo(VALUE_SOURCE_OVERRIDDEN)

        val frozen = valuations.findAllBySnapshotId(dto.id).single { it.holdingId == holding.id }
        assertThat(frozen.quantity).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(frozen.unitPrice).isEqualByComparingTo(BigDecimal("60000"))
        assertThat(frozen.priceAsOf).isEqualTo(date.minusDays(1))
        assertThat(frozen.valueBase).isEqualByComparingTo(BigDecimal("30000.00"))
        assertThat(frozen.stale).isFalse()
    }

    @Test
    fun `null source is inferred from the submitted value`() {
        val (user, household) = seed()
        seedPricedBtc(household, user, price = "60000", quantity = "0.5")

        val matching = snapshotService.create(
            household.id,
            request(assets = fullAssetSet("crypto" to input("30000.00", null)), snapshotDate = date),
            user,
        )
        assertThat(matching.assets.single { it.assetClassCode == "crypto" }.valueSource)
            .isEqualTo(VALUE_SOURCE_COMPUTED)

        val edited = snapshotService.create(
            household.id,
            request(assets = fullAssetSet("crypto" to input("31000.00", null)), snapshotDate = date.plusDays(1)),
            user,
        )
        assertThat(edited.assets.single { it.assetClassCode == "crypto" }.valueSource)
            .isEqualTo(VALUE_SOURCE_OVERRIDDEN)
        assertThat(edited.assets.single { it.assetClassCode == "crypto" }.value)
            .isEqualByComparingTo(BigDecimal("31000.00"))
    }

    @Test
    fun `explicit computed on a class without holdings is rejected`() {
        val (user, household) = seed()
        assertThatThrownBy {
            snapshotService.create(
                household.id,
                request(assets = fullAssetSet("stocks" to input("100.00", VALUE_SOURCE_COMPUTED))),
                user,
            )
        }.isInstanceOf(AppException::class.java)
            .hasFieldOrPropertyWithValue("code", "SNAPSHOT_VALUE_SOURCE_INVALID")
    }

    @Test
    fun `unlinked holdings freeze at zero and flag the snapshot stale`() {
        val (user, household) = seed()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.fund, symbol = "MSCIW"),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = date.minusDays(30), quantity = BigDecimal("10"), unitPrice = BigDecimal("100")),
            user,
        )

        val dto = snapshotService.create(household.id, request(assets = fullAssetSet()), user)

        val frozen = valuations.findAllBySnapshotId(dto.id).single { it.holdingId == holding.id }
        assertThat(frozen.valueBase).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(frozen.unitPrice).isNull()
        assertThat(frozen.stale).isTrue()
    }

    @Test
    fun `updating the snapshot date re-freezes the valuations`() {
        val (user, household) = seed()
        val holding = seedPricedBtc(household, user, price = "60000", quantity = "1")
        storePrice(lastCoinId, date.plusDays(9), "70000")

        val dto = snapshotService.create(household.id, request(assets = fullAssetSet()), user)
        val before = valuations.findAllBySnapshotId(dto.id).single { it.holdingId == holding.id }
        assertThat(before.unitPrice).isEqualByComparingTo(BigDecimal("60000"))

        snapshotService.update(
            household.id, dto.id,
            request(assets = fullAssetSet(), snapshotDate = date.plusDays(10)),
            user,
        )

        val after = valuations.findAllBySnapshotId(dto.id).single { it.holdingId == holding.id }
        assertThat(after.unitPrice).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(after.priceAsOf).isEqualTo(date.plusDays(9))
    }

    @Test
    fun `snapshot freezes the net quantity as of its date`() {
        val (user, household) = seed()
        val holding = seedPricedBtc(household, user, price = "60000", quantity = "1")
        // Sell 0.4 five days after the first snapshot date.
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(
                type = LotType.SELL,
                tradedOn = date.plusDays(5),
                quantity = BigDecimal("0.4"),
                unitPrice = BigDecimal("65000"),
            ),
            user,
        )

        val beforeSale = snapshotService.create(household.id, request(assets = fullAssetSet()), user)
        assertThat(valuations.findAllBySnapshotId(beforeSale.id).single { it.holdingId == holding.id }.quantity)
            .isEqualByComparingTo(BigDecimal("1"))

        val afterSale = snapshotService.create(
            household.id,
            request(assets = fullAssetSet(), snapshotDate = date.plusDays(10)),
            user,
        )
        assertThat(valuations.findAllBySnapshotId(afterSale.id).single { it.holdingId == holding.id }.quantity)
            .isEqualByComparingTo(BigDecimal("0.6"))
    }

    @Test
    fun `deleting a snapshot removes its frozen valuations`() {
        val (user, household) = seed()
        seedPricedBtc(household, user, price = "60000", quantity = "1")
        val dto = snapshotService.create(household.id, request(assets = fullAssetSet()), user)
        assertThat(valuations.findAllBySnapshotId(dto.id)).isNotEmpty()

        snapshotService.delete(household.id, dto.id, user)

        assertThat(valuations.findAllBySnapshotId(dto.id)).isEmpty()
    }

    // --- helpers ---

    private data class AssetInput(val value: String, val source: String?)

    private fun input(value: String, source: String?) = AssetInput(value, source)

    private fun fullAssetSet(vararg overrides: Pair<String, AssetInput>): List<AssetValueInput> {
        val byCode = overrides.toMap()
        return listOf("cash", "fund", "etfs", "stocks", "crypto", "pension").map { code ->
            val override = byCode[code]
            AssetValueInput(code, BigDecimal(override?.value ?: "0.00"), override?.source)
        }
    }

    private fun request(assets: List<AssetValueInput>, snapshotDate: LocalDate = date) = SnapshotRequest(
        snapshotDate = snapshotDate,
        assets = assets,
        liabilities = emptyList(),
        confirmLargeChanges = true,
    )

    // price_history rows are global (shared coordinates), so every test gets its own coin id.
    private var lastCoinId: String = ""

    private fun seedPricedBtc(household: Household, user: User, price: String, quantity: String): HoldingDto {
        lastCoinId = "btc-${System.nanoTime()}"
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "BTC",
                provider = HoldingProvider.coingecko,
                providerSymbol = lastCoinId,
            ),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = date.minusDays(30), quantity = BigDecimal(quantity), unitPrice = BigDecimal("50000")),
            user,
        )
        storePrice(lastCoinId, date.minusDays(1), price)
        return holding
    }

    private fun storePrice(coinId: String, priceDate: LocalDate, price: String) {
        prices.save(
            PricePoint(
                provider = "coingecko",
                providerSymbol = coinId,
                currency = "EUR",
                price = BigDecimal(price),
                priceDate = priceDate,
                asOf = Instant.now(),
            )
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "sp${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
