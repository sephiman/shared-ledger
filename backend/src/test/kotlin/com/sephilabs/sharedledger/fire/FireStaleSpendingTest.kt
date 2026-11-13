package com.sephilabs.sharedledger.fire

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
import java.time.YearMonth

/** The photo flags a spending window whose latest complete months have no expenses at all (data stopped coming in). */
class FireStaleSpendingTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val transactions: TransactionService,
    private val fireService: FireService,
) : IntegrationTestBase() {

    private val thisMonth = YearMonth.now()

    @Test
    fun `names the last month with spending when the last two complete months have none`() {
        val (h, u) = household()
        spendMonthly(h, u, from = thisMonth.minusMonths(14), to = thisMonth.minusMonths(3))

        assertThat(fireService.project(h.id).spending.noSpendingSince).isEqualTo(thisMonth.minusMonths(3))
    }

    @Test
    fun `one empty complete month is not stale`() {
        val (h, u) = household()
        spendMonthly(h, u, from = thisMonth.minusMonths(12), to = thisMonth.minusMonths(2))

        assertThat(fireService.project(h.id).spending.noSpendingSince).isNull()
    }

    @Test
    fun `spending in the current, still open month means data is coming in again`() {
        val (h, u) = household()
        spendMonthly(h, u, from = thisMonth.minusMonths(12), to = thisMonth.minusMonths(4))
        spendMonthly(h, u, from = thisMonth, to = thisMonth)

        assertThat(fireService.project(h.id).spending.noSpendingSince).isNull()
    }

    @Test
    fun `with the current month excluded the window ends last month, so the two months before it are checked`() {
        val (h, u) = household(excludeCurrentMonth = true)
        spendMonthly(h, u, from = thisMonth.minusMonths(12), to = thisMonth.minusMonths(3))
        spendMonthly(h, u, from = thisMonth, to = thisMonth) // outside the window, so it does not count

        assertThat(fireService.project(h.id).spending.noSpendingSince).isEqualTo(thisMonth.minusMonths(3))
    }

    @Test
    fun `typed-in spending bases are not judged by transactions`() {
        val (h, u) = household(manualSpending = true)
        spendMonthly(h, u, from = thisMonth.minusMonths(14), to = thisMonth.minusMonths(3))

        assertThat(fireService.project(h.id).spending.noSpendingSince).isNull()
    }

    @Test
    fun `the notice does not change the calculation`() {
        val (h, u) = household()
        spendMonthly(h, u, from = thisMonth.minusMonths(14), to = thisMonth.minusMonths(3))

        val photo = fireService.project(h.id)
        // 12 months in the window, 9 of them with 1,000 of spending: the average still counts the empty months.
        assertThat(photo.spending.totalMonthly).isEqualByComparingTo("750.00")
    }

    // -------------------------------------------------------------------------------------

    private fun household(excludeCurrentMonth: Boolean = false, manualSpending: Boolean = false): Pair<Household, User> {
        val user = users.save(User(email = "stale-${System.nanoTime()}@example.com", passwordHash = "x"))
        val h = households.save(Household(name = "Stale", currency = "EUR"))
        val request = fireSettingsRequest(
            totalSpendingMode = if (manualSpending) SpendingBaseMode.manual else SpendingBaseMode.derived,
            manualTotalSpending = if (manualSpending) "2000.00" else "0.00",
        ).copy(
            excludeCurrentMonth = excludeCurrentMonth,
            essentialSpendingMode = if (manualSpending) SpendingBaseMode.manual else SpendingBaseMode.derived,
            manualEssentialSpending = if (manualSpending) BigDecimal("1000.00") else BigDecimal.ZERO,
        )
        fireService.update(h.id, request, user)
        return h to user
    }

    private fun spendMonthly(h: Household, u: User, from: YearMonth, to: YearMonth) {
        generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.forEach { month ->
            transactions.create(
                h.id,
                TransactionRequest(
                    occurrenceDate = month.atDay(1),
                    direction = Direction.expense,
                    categoryCode = "groceries.groceries",
                    amount = BigDecimal("1000.00"),
                    description = null,
                ),
                u,
            )
        }
    }
}
