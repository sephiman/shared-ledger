package com.sephilabs.sharedledger.budget

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class BudgetRefundTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: BudgetService,
    private val transactions: TransactionService,
) : IntegrationTestBase() {

    @Test
    fun `a refund gives budget back to the category it came from`() {
        val (user, household) = seed()
        service.upsert(household.id, BudgetUpsertRequest(listOf(budget("groceries.groceries", "200.00"))), user)
        spend(household, user, day = 4, amount = "100.00")
        refund(household, user, day = 18, amount = "-30.00")

        val row = service.monthSummary(household.id, 2025, 5).rows.first { it.categoryCode == "groceries.groceries" }

        // €100 out, €30 back: €70 of the €200 budget used.
        assertThat(row.spent).isEqualByComparingTo("70.00")
        assertThat(row.percent).isEqualTo(35.0)
        assertThat(row.projection.signum()).isPositive()
    }

    @Test
    fun `a category that ends the month net-negative reports the real figure but no negative pace`() {
        val (user, household) = seed()
        service.upsert(household.id, BudgetUpsertRequest(listOf(budget("groceries.groceries", "200.00"))), user)
        spend(household, user, day = 4, amount = "40.00")
        refund(household, user, day = 20, amount = "-90.00")

        val row = service.monthSummary(household.id, 2025, 5).rows.first { it.categoryCode == "groceries.groceries" }

        // The money is what it is; a pace or a percentage of budget "used" is not meaningful below zero.
        assertThat(row.spent).isEqualByComparingTo("-50.00")
        assertThat(row.pace).isEqualByComparingTo("0.00")
        assertThat(row.projection).isEqualByComparingTo("0.00")
        assertThat(row.percent).isEqualTo(0.0)
    }

    private fun budget(code: String, amount: String) =
        BudgetUpsertItem(year = 2025, month = 5, categoryCode = code, amount = BigDecimal(amount))

    private fun spend(h: Household, u: User, day: Int, amount: String) {
        transactions.create(h.id, request(day, amount, isRefund = false), u)
    }

    private fun refund(h: Household, u: User, day: Int, amount: String) {
        transactions.create(h.id, request(day, amount, isRefund = true), u)
    }

    private fun request(day: Int, amount: String, isRefund: Boolean) = TransactionRequest(
        occurrenceDate = LocalDate.of(2025, 5, day),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(amount),
        isRefund = isRefund,
    )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "br${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
