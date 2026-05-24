package com.sharedledger.networth.movement

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

class MovementPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: MovementService,
    private val repo: MovementRepository,
) : IntegrationTestBase() {

    @Test
    fun `update persists amount`() {
        val (user, household) = seed()
        val m = service.create(household.id, sampleRequest(amount = "500.00"), user)

        service.update(household.id, m.id, sampleRequest(amount = "750.25"), user)

        val reloaded = repo.findById(m.id).orElseThrow()
        assertThat(reloaded.amount).isEqualByComparingTo("750.25")
        assertThat(reloaded.updatedByUserId).isEqualTo(user.id)
    }

    @Test
    fun `delete marks deletedAt`() {
        val (user, household) = seed()
        val m = service.create(household.id, sampleRequest(), user)

        service.delete(household.id, m.id, user)

        val hidden = repo.findAll().find { it.id == m.id }
        assertThat(hidden).isNull()
    }

    private fun sampleRequest(amount: String = "500.00") = MovementRequest(
        movementDate = LocalDate.of(2025, 3, 1),
        type = MovementType.contribution,
        assetClassCode = "etfs",
        liabilityId = null,
        amount = BigDecimal(amount),
        description = null,
    )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "mv${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
