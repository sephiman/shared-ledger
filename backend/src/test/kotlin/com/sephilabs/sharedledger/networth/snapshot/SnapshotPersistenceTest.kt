package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SnapshotPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
    private val service: SnapshotService,
    private val repo: SnapshotRepository,
) : IntegrationTestBase() {

    private val allAssetCodes = listOf("cash", "fund", "etfs", "stocks", "crypto", "pension")

    @Test
    fun `delete removes the snapshot`() {
        val (user, household) = seed()
        val snapshot = Snapshot(
            householdId = household.id,
            snapshotDate = LocalDate.of(2025, 4, 1),
            createdByUserId = user.id,
            updatedByUserId = user.id,
        )
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), BigDecimal("1000.00")))
        repo.save(snapshot)

        service.delete(household.id, snapshot.id, user)

        assertThat(repo.findById(snapshot.id)).isEmpty
    }

    @Test
    fun `update replaces date, note, asset values, and liability balances`() {
        val (user, household) = seed()
        val mortgage = liabilities.save(
            Liability(
                householdId = household.id,
                name = "Mortgage",
                active = true,
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        val created = service.create(
            household.id,
            buildRequest(
                date = LocalDate.of(2026, 3, 1),
                note = "March",
                assetValues = mapOf("cash" to "100.00"),
                liab = mapOf(mortgage.id to "1000.00"),
            ),
            user,
        )

        service.update(
            household.id,
            created.id,
            buildRequest(
                date = LocalDate.of(2026, 3, 15),
                note = "March (corrected)",
                assetValues = mapOf("cash" to "250.00", "etfs" to "500.00"),
                liab = mapOf(mortgage.id to "900.00"),
            ),
            user,
        )

        val reloaded = repo.findById(created.id).orElseThrow()
        assertThat(reloaded.snapshotDate).isEqualTo(LocalDate.of(2026, 3, 15))
        assertThat(reloaded.note).isEqualTo("March (corrected)")
        val byCode = reloaded.assetValues.associate { it.id.assetClassCode to it.value }
        assertThat(byCode["cash"]).isEqualByComparingTo("250.00")
        assertThat(byCode["etfs"]).isEqualByComparingTo("500.00")
        assertThat(reloaded.liabilityBalances.single().balance).isEqualByComparingTo("900.00")
    }

    @Test
    fun `update sets updatedByUserId to the editor`() {
        val (creator, household) = seed()
        val editor = users.save(User(email = "ed${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val created = service.create(household.id, buildRequest(), creator)

        service.update(household.id, created.id, buildRequest(note = "later edit"), editor)

        val reloaded = repo.findById(created.id).orElseThrow()
        assertThat(reloaded.updatedByUserId).isEqualTo(editor.id)
        assertThat(reloaded.createdByUserId).isEqualTo(creator.id)
    }

    @Test
    fun `update rejects request that omits an asset class`() {
        val (user, household) = seed()
        val created = service.create(household.id, buildRequest(), user)
        val incomplete = SnapshotRequest(
            snapshotDate = LocalDate.of(2026, 1, 2),
            note = null,
            assets = listOf(AssetValueInput("cash", BigDecimal("1.00"))), // only 1 of 6
            liabilities = emptyList(),
            confirmLargeChanges = true,
        )

        assertThatThrownBy {
            service.update(household.id, created.id, incomplete, user)
        }.isInstanceOfSatisfying(AppException::class.java) { e ->
            assertThat(e.code).isEqualTo("SNAPSHOT_MISSING_ASSET_VALUES")
        }
    }

    @Test
    fun `update of snapshot belonging to another household throws NOT_FOUND`() {
        val (user, householdA) = seed()
        val householdB = households.save(Household(name = "B", currency = "EUR", defaultLocale = "en"))
        val created = service.create(householdA.id, buildRequest(), user)

        assertThatThrownBy {
            service.update(householdB.id, created.id, buildRequest(note = "tampered"), user)
        }.isInstanceOfSatisfying(AppException::class.java) { e ->
            assertThat(e.code).isEqualTo("SNAPSHOT_NOT_FOUND")
        }
    }

    private fun buildRequest(
        date: LocalDate = LocalDate.of(2026, 1, 1),
        note: String? = null,
        assetValues: Map<String, String> = emptyMap(),
        liab: Map<UUID, String> = emptyMap(),
    ): SnapshotRequest {
        val assets = allAssetCodes.map { code ->
            AssetValueInput(assetClassCode = code, value = BigDecimal(assetValues[code] ?: "0.00"))
        }
        val liabilitiesInput = liab.map { (id, balance) ->
            LiabilityBalanceInput(liabilityId = id, balance = BigDecimal(balance))
        }
        return SnapshotRequest(
            snapshotDate = date,
            note = note,
            assets = assets,
            liabilities = liabilitiesInput,
            confirmLargeChanges = true,
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "sn${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
