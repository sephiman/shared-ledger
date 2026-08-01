package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class AutoSnapshotIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val holdingService: HoldingService,
    private val snapshotService: SnapshotService,
    private val autoSnapshots: AutoSnapshotService,
    private val snapshots: SnapshotRepository,
    private val prices: PricePointRepository,
) : IntegrationTestBase() {

    private val allCodes = listOf("cash", "fund", "etfs", "stocks", "crypto", "pension")
    private val runDate = LocalDate.of(2026, 6, 15)

    @Test
    fun `job fills market classes from the portfolio and carries the rest over`() {
        val (user, household) = seed()
        // A previous snapshot with a manual cash balance to be carried forward.
        snapshotService.create(household.id, manualRequest(runDate.minusDays(30), "cash" to "1000.00"), user)
        seedPricedBtc(household, user, valuedAt = runDate, price = "60000", quantity = "0.5")

        val created = autoSnapshots.runForHousehold(household.id, SnapshotFrequency.daily, runDate)

        assertThat(created).isTrue()
        val snap = snapshots.findUpTo(household.id, runDate).first { it.snapshotDate == runDate }
        val bySource = snapshotService.toDto(snap).assets.associateBy { it.assetClassCode }
        // crypto: fresh from the portfolio (0.5 × 60000).
        assertThat(bySource.getValue("crypto").value).isEqualByComparingTo(BigDecimal("30000.00"))
        assertThat(bySource.getValue("crypto").valueSource).isEqualTo(VALUE_SOURCE_COMPUTED)
        // cash: carried over from the previous snapshot.
        assertThat(bySource.getValue("cash").value).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(bySource.getValue("cash").valueSource).isEqualTo(VALUE_SOURCE_CARRIED_OVER)
    }

    @Test
    fun `carried-over values are distinguishable from fresh portfolio values`() {
        val (user, household) = seed()
        snapshotService.create(household.id, manualRequest(runDate.minusDays(10), "cash" to "500.00", "pension" to "20000.00"), user)
        seedPricedBtc(household, user, valuedAt = runDate, price = "40000", quantity = "1")

        autoSnapshots.runForHousehold(household.id, SnapshotFrequency.daily, runDate)

        val assets = snapshotService.toDto(snapshots.findUpTo(household.id, runDate).first { it.snapshotDate == runDate }).assets
        val carried = assets.filter { it.valueSource == VALUE_SOURCE_CARRIED_OVER }.map { it.assetClassCode }.toSet()
        val computed = assets.filter { it.valueSource == VALUE_SOURCE_COMPUTED }.map { it.assetClassCode }.toSet()
        assertThat(computed).containsExactly("crypto")
        assertThat(carried).contains("cash", "pension", "fund", "etfs", "stocks")
    }

    @Test
    fun `first-ever run leaves the manual classes empty`() {
        val (user, household) = seed()
        seedPricedBtc(household, user, valuedAt = runDate, price = "40000", quantity = "1")

        autoSnapshots.runForHousehold(household.id, SnapshotFrequency.daily, runDate)

        val assets = snapshotService.toDto(snapshots.findUpTo(household.id, runDate).first { it.snapshotDate == runDate }).assets
        val cash = assets.first { it.assetClassCode == "cash" }
        assertThat(cash.value).isEqualByComparingTo(BigDecimal.ZERO)
        // Nothing to carry, nothing computed -> a plain empty manual class, not "carried over".
        assertThat(cash.valueSource).isEqualTo(VALUE_SOURCE_OVERRIDDEN)
        assertThat(assets.first { it.assetClassCode == "crypto" }.value).isEqualByComparingTo(BigDecimal("40000.00"))
    }

    @Test
    fun `no duplicate is created and an existing edited snapshot is never overwritten`() {
        val (user, household) = seed()
        // The user already saved a snapshot for the run date with an edited cash balance.
        snapshotService.create(household.id, manualRequest(runDate, "cash" to "9999.00"), user)
        seedPricedBtc(household, user, valuedAt = runDate, price = "40000", quantity = "1")

        val created = autoSnapshots.runForHousehold(household.id, SnapshotFrequency.daily, runDate)

        assertThat(created).isFalse()
        val onDate = snapshots.findUpTo(household.id, runDate).filter { it.snapshotDate == runDate }
        assertThat(onDate).hasSize(1)
        // The user's value is intact.
        val cash = snapshotService.toDto(onDate.single()).assets.first { it.assetClassCode == "cash" }
        assertThat(cash.value).isEqualByComparingTo(BigDecimal("9999.00"))
    }

    @Test
    fun `nothing is created while the toggle is off`() {
        val (user, household) = seed()
        seedPricedBtc(household, user, valuedAt = runDate, price = "40000", quantity = "1")
        // Settings default to disabled; runForAll only touches enabled households.
        autoSnapshots.getOrCreate(household.id)

        val created = autoSnapshots.runForAll(runDate)

        assertThat(created).isZero()
        assertThat(snapshots.findAllOrdered(household.id)).isEmpty()

        // Flip it on and the same run now produces one.
        autoSnapshots.update(household.id, enabled = true, frequency = SnapshotFrequency.daily, by = user)
        autoSnapshots.runForAll(runDate)
        assertThat(snapshots.findUpTo(household.id, runDate).any { it.snapshotDate == runDate }).isTrue()
    }

    @Test
    fun `frequency gates the due date`() {
        val (user, household) = seed()
        seedPricedBtc(household, user, valuedAt = runDate, price = "40000", quantity = "1")
        // 2026-06-15 is a Monday; weekly (default Monday) is due, monthly (day 1) is not.
        assertThat(runDate.dayOfWeek).isEqualTo(java.time.DayOfWeek.MONDAY)

        assertThat(autoSnapshots.runForHousehold(household.id, SnapshotFrequency.monthly, runDate)).isFalse()
        assertThat(autoSnapshots.runForHousehold(household.id, SnapshotFrequency.weekly, runDate)).isTrue()
    }

    // --- helpers ---

    private fun manualRequest(date: LocalDate, vararg overrides: Pair<String, String>): SnapshotRequest {
        val byCode = overrides.toMap()
        return SnapshotRequest(
            snapshotDate = date,
            assets = allCodes.map { AssetValueInput(it, BigDecimal(byCode[it] ?: "0.00")) },
            liabilities = emptyList(),
            confirmLargeChanges = true,
        )
    }

    private fun seedPricedBtc(household: Household, user: User, valuedAt: LocalDate, price: String, quantity: String) {
        val coinId = "btc-${System.nanoTime()}"
        val holding = holdingService.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "BTC",
                provider = HoldingProvider.coingecko,
                providerSymbol = coinId,
            ),
            user,
        )
        holdingService.addLot(
            household.id, holding.id,
            LotRequest(tradedOn = valuedAt.minusDays(40), quantity = BigDecimal(quantity), unitPrice = BigDecimal("50000")),
            user,
        )
        prices.save(
            PricePoint(
                provider = "coingecko",
                providerSymbol = coinId,
                currency = "EUR",
                price = BigDecimal(price),
                priceDate = valuedAt,
                asOf = Instant.now(),
            )
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "as${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        return user to household
    }
}
