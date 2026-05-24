package com.sharedledger.networth.snapshot

import com.sharedledger.IntegrationTestBase
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class SnapshotPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: SnapshotService,
    private val repo: SnapshotRepository,
) : IntegrationTestBase() {

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

        service.delete(household.id, snapshot.id)

        assertThat(repo.findById(snapshot.id)).isEmpty
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "sn${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
