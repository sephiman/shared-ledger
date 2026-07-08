package com.sephilabs.sharedledger.networth

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.asset.Asset
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.asset.AssetType
import com.sephilabs.sharedledger.networth.asset.AssetValueEntry
import com.sephilabs.sharedledger.networth.asset.AssetValueEntryRepository
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.snapshot.AssetValueInput
import com.sephilabs.sharedledger.networth.snapshot.LiabilityBalanceInput
import com.sephilabs.sharedledger.networth.snapshot.NamedAssetValueInput
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRequest
import com.sephilabs.sharedledger.networth.snapshot.SnapshotService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Named assets & liabilities feed their own dated series into snapshots: named assets add to the
 * assets total, net worth = assets − liabilities, and names are resolved (incl. soft-deleted).
 */
class NamedValueSnapshotIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val assets: AssetRepository,
    private val assetValues: AssetValueEntryRepository,
    private val liabilities: LiabilityRepository,
    private val namedValues: NamedValueResolver,
    private val service: SnapshotService,
    private val snapshots: SnapshotRepository,
) : IntegrationTestBase() {

    private val allAssetCodes = listOf("cash", "fund", "etfs", "stocks", "crypto", "pension")

    @Test
    fun `named asset adds to assets total and net worth`() {
        val (user, household) = seed()
        val house = assets.save(asset(household.id, user.id, "House", AssetType.property))
        val mortgage = liabilities.save(liability(household.id, user.id, "Mortgage"))

        val dto = service.create(
            household.id,
            request(
                named = mapOf(house.id to "450000.00"),
                liab = mapOf(mortgage.id to "300000.00"),
            ),
            user,
        )

        assertThat(dto.totalAssets).isEqualByComparingTo("450000.00")
        assertThat(dto.totalLiabilities).isEqualByComparingTo("300000.00")
        assertThat(dto.netWorth).isEqualByComparingTo("150000.00")
        assertThat(dto.namedAssets.single().name).isEqualTo("House")
        assertThat(dto.namedAssets.single().value).isEqualByComparingTo("450000.00")
    }

    @Test
    fun `snapshot resolves the name of a soft-deleted liability instead of a UUID`() {
        val (user, household) = seed()
        val mortgage = liabilities.save(liability(household.id, user.id, "Old mortgage"))
        val created = service.create(household.id, request(liab = mapOf(mortgage.id to "1000.00")), user)

        // Soft-delete the liability, then re-render the (historical) snapshot.
        mortgage.deletedAt = Instant.now()
        liabilities.save(mortgage)

        val reloaded = service.toDto(snapshots.findById(created.id).orElseThrow())
        val row = reloaded.liabilities.single()
        assertThat(row.liabilityName).isEqualTo("Old mortgage")
        assertThat(row.liabilityName).isNotEqualTo(mortgage.id.toString())
    }

    @Test
    fun `create rejects a request missing an active named asset`() {
        val (user, household) = seed()
        assets.save(asset(household.id, user.id, "House", AssetType.property))

        assertThatThrownBy {
            service.create(household.id, request(named = emptyMap()), user)
        }.isInstanceOfSatisfying(AppException::class.java) { e ->
            assertThat(e.code).isEqualTo("SNAPSHOT_MISSING_NAMED_ASSET_VALUES")
        }
    }

    @Test
    fun `resolver returns the latest value on or before the date`() {
        val (user, household) = seed()
        val house = assets.save(asset(household.id, user.id, "House", AssetType.property))
        assetValues.save(valueEntry(house.id, user.id, LocalDate.of(2026, 1, 15), "450000.00"))
        assetValues.save(valueEntry(house.id, user.id, LocalDate.of(2026, 6, 20), "500000.00"))

        assertThat(namedValues.assetValueAt(house.id, LocalDate.of(2026, 3, 1))).isEqualByComparingTo("450000.00")
        assertThat(namedValues.assetValueAt(house.id, LocalDate.of(2026, 7, 1))).isEqualByComparingTo("500000.00")
        assertThat(namedValues.assetValueAt(house.id, LocalDate.of(2025, 12, 1))).isNull()
    }

    private fun request(
        date: LocalDate = LocalDate.of(2026, 7, 1),
        named: Map<UUID, String> = emptyMap(),
        liab: Map<UUID, String> = emptyMap(),
    ) = SnapshotRequest(
        snapshotDate = date,
        note = null,
        assets = allAssetCodes.map { AssetValueInput(it, BigDecimal("0.00")) },
        liabilities = liab.map { (id, b) -> LiabilityBalanceInput(id, BigDecimal(b)) },
        namedAssets = named.map { (id, v) -> NamedAssetValueInput(id, BigDecimal(v)) },
        confirmLargeChanges = true,
    )

    private fun asset(householdId: UUID, userId: UUID, name: String, type: AssetType) =
        Asset(householdId = householdId, name = name, type = type, createdByUserId = userId, updatedByUserId = userId)

    private fun liability(householdId: UUID, userId: UUID, name: String) =
        Liability(householdId = householdId, name = name, createdByUserId = userId, updatedByUserId = userId)

    private fun valueEntry(assetId: UUID, userId: UUID, date: LocalDate, value: String) =
        AssetValueEntry(
            assetId = assetId,
            valueDate = date,
            value = BigDecimal(value),
            createdByUserId = userId,
            updatedByUserId = userId,
        )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "nv${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
