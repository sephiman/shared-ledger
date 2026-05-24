package com.sharedledger.networth.liability

import com.sharedledger.IntegrationTestBase
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class LiabilityPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
    private val txManager: PlatformTransactionManager,
) : IntegrationTestBase() {

    @Test
    fun `update persists name and active flag`() {
        val (user, household) = seed()
        val saved = liabilities.save(Liability(
            householdId = household.id,
            name = "Old name",
            active = true,
            createdByUserId = user.id,
            updatedByUserId = user.id,
        ))

        // Simulate the controller's @Transactional path.
        TransactionTemplate(txManager).execute {
            val liability = liabilities.findById(saved.id).orElseThrow()
            liability.name = "New name"
            liability.active = false
            liability.updatedByUserId = user.id
        }

        val reloaded = liabilities.findById(saved.id).orElseThrow()
        assertThat(reloaded.name).isEqualTo("New name")
        assertThat(reloaded.active).isFalse
    }

    @Test
    fun `delete marks deletedAt`() {
        val (user, household) = seed()
        val saved = liabilities.save(Liability(
            householdId = household.id,
            name = "Goner ${System.nanoTime()}",
            active = true,
            createdByUserId = user.id,
            updatedByUserId = user.id,
        ))

        TransactionTemplate(txManager).execute {
            val liability = liabilities.findById(saved.id).orElseThrow()
            liability.deletedAt = java.time.Instant.now()
        }

        val hidden = liabilities.findAllByHouseholdIdOrderByNameAsc(household.id).find { it.id == saved.id }
        assertThat(hidden).isNull()
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "li${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
