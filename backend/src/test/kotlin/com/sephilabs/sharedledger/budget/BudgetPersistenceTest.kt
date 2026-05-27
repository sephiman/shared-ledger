package com.sephilabs.sharedledger.budget

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class BudgetPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: BudgetService,
    private val repo: BudgetRepository,
) : IntegrationTestBase() {

    @Test
    fun `upsert inserts then updates the same row`() {
        val (user, household) = seed()

        service.upsert(household.id, BudgetUpsertRequest(listOf(item("groceries.groceries", "350.00"))), user)
        val first = repo.findAllByHouseholdIdAndYearAndMonth(household.id, 2025, 5).single()
        assertThat(first.amount).isEqualByComparingTo("350.00")

        service.upsert(household.id, BudgetUpsertRequest(listOf(item("groceries.groceries", "425.50"))), user)
        val second = repo.findAllByHouseholdIdAndYearAndMonth(household.id, 2025, 5).single()
        assertThat(second.id).isEqualTo(first.id) // same row, not a new one
        assertThat(second.amount).isEqualByComparingTo("425.50")
        assertThat(second.updatedByUserId).isEqualTo(user.id)
    }

    @Test
    fun `delete removes the row`() {
        val (user, household) = seed()
        val saved = service.upsert(household.id, BudgetUpsertRequest(listOf(item("groceries.groceries", "100.00"))), user).single()

        service.delete(household.id, saved.id)

        assertThat(repo.findById(saved.id)).isEmpty
    }

    private fun item(code: String, amount: String) =
        BudgetUpsertItem(year = 2025, month = 5, categoryCode = code, amount = BigDecimal(amount))

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "bd${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
