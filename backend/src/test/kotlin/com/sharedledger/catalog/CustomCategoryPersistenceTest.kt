package com.sharedledger.catalog

import com.sharedledger.IntegrationTestBase
import com.sharedledger.budget.BudgetRepository
import com.sharedledger.budget.BudgetService
import com.sharedledger.budget.BudgetUpsertItem
import com.sharedledger.budget.BudgetUpsertRequest
import com.sharedledger.common.errors.AppException
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import com.sharedledger.transaction.Direction
import com.sharedledger.transaction.TransactionRepository
import com.sharedledger.transaction.TransactionRequest
import com.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class CustomCategoryPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val writer: CustomCategoryWriter,
    private val service: CategoryService,
    private val customs: CustomCategoryRepository,
    private val transactions: TransactionRepository,
    private val transactionService: TransactionService,
    private val budgets: BudgetRepository,
    private val budgetService: BudgetService,
) : IntegrationTestBase() {

    @Test
    fun `create derives the code from group and slug`() {
        val (user, household) = seed()

        val created = writer.create(household.id, "Café meals", "expense", "outings", essential = false, by = user)

        assertThat(created.id.code).isEqualTo("outings.cafe_meals")
        assertThat(created.name).isEqualTo("Café meals")
    }

    @Test
    fun `create rejects collision with a global code`() {
        val (user, household) = seed()
        assertThatThrownBy {
            writer.create(household.id, "Rent", "expense", "home", essential = true, by = user)
        }.isInstanceOfSatisfying(AppException::class.java) { e ->
            assertThat(e.code).isEqualTo("CATEGORY_CODE_CONFLICT")
        }
    }

    @Test
    fun `listForHousehold unions globals and customs but isolates them per household`() {
        val (user, householdA) = seed()
        val householdB = households.save(Household(name = "Other", currency = "EUR", defaultLocale = "en"))

        writer.create(householdA.id, "Pet food", "expense", "home", essential = false, by = user)
        writer.create(householdB.id, "Boat fuel", "expense", "transport", essential = false, by = user)

        val listA = service.listForHousehold(householdA.id)
        val codesA = listA.map { it.code }
        assertThat(codesA).contains("home.pet_food", "home.rent")
        assertThat(codesA).doesNotContain("transport.boat_fuel")

        val listB = service.listForHousehold(householdB.id)
        val codesB = listB.map { it.code }
        assertThat(codesB).contains("transport.boat_fuel")
        assertThat(codesB).doesNotContain("home.pet_food")
    }

    @Test
    fun `delete cascades transactions and budgets belonging to the custom code`() {
        val (user, household) = seed()
        val created = writer.create(household.id, "Pet food", "expense", "home", essential = false, by = user)

        transactionService.create(
            household.id,
            TransactionRequest(
                occurrenceDate = LocalDate.now(),
                direction = Direction.expense,
                categoryCode = created.id.code,
                amount = BigDecimal("12.50"),
                description = null,
            ),
            user,
        )
        budgetService.upsert(
            household.id,
            BudgetUpsertRequest(listOf(BudgetUpsertItem(2025, 5, created.id.code, BigDecimal("50.00")))),
            user,
        )

        writer.delete(household.id, created.id.code)

        assertThat(customs.findByIdHouseholdIdAndIdCode(household.id, created.id.code)).isNull()
        assertThat(transactions.findAll().any { it.categoryCode == created.id.code }).isFalse()
        assertThat(budgets.findAll().any { it.categoryCode == created.id.code }).isFalse()
    }

    @Test
    fun `transaction create with mismatched direction is rejected by CategoryService`() {
        val (user, household) = seed()
        writer.create(household.id, "Side gig", "income", null, essential = false, by = user)

        assertThatThrownBy {
            transactionService.create(
                household.id,
                TransactionRequest(
                    occurrenceDate = LocalDate.now(),
                    direction = Direction.expense,        // mismatch — should fail
                    categoryCode = "income.side_gig",
                    amount = BigDecimal("100.00"),
                    description = null,
                ),
                user,
            )
        }.isInstanceOfSatisfying(AppException::class.java) { e ->
            assertThat(e.code).isEqualTo("CATEGORY_DIRECTION_MISMATCH")
        }
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "cc${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
