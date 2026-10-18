package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringService
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.recurring.RecurringTemplateRequest
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class MoneyFlowProjectionTest @Autowired constructor(
    private val service: AnalyticsService,
    private val transactions: TransactionRepository,
    private val recurring: RecurringService,
    private val templates: RecurringTemplateRepository,
    private val users: UserRepository,
    private val households: HouseholdRepository,
) : IntegrationTestBase() {

    private val today = LocalDate.of(2025, 10, 18)
    private val october = YearMonth.from(today)

    @Test
    fun `projects actuals plus recurring still due plus the unspent discretionary average`() {
        val (user, household) = seed()
        val rent = template(household, user, Direction.expense, "home.rent", "600.00", dayOfMonth = 1)
        val salary = template(household, user, Direction.income, "income.salary", "2000.00", dayOfMonth = 25)
        for ((ym, groceries) in listOf(july to "300.00", august to "330.00", september to "360.00")) {
            tx(household, user, ym.atDay(1), Direction.expense, "home.rent", "600.00", rent.id)
            tx(household, user, ym.atDay(25), Direction.income, "income.salary", "2000.00", salary.id)
            tx(household, user, ym.atDay(10), Direction.expense, "groceries.groceries", groceries)
        }
        tx(household, user, october.atDay(1), Direction.expense, "home.rent", "600.00", rent.id)
        tx(household, user, october.atDay(9), Direction.expense, "groceries.groceries", "200.00")

        val projection = service.moneyFlowProjection(household.id, today, "category", 3)

        // Groceries: 200 spent of a 330 average; rent already fired; salary still due on the 25th.
        assertThat(components(projection, "expense", "groceries.groceries"))
            .containsExactly("200.00", "0.00", "130.00", "330.00")
        assertThat(components(projection, "expense", "home.rent")).containsExactly("600.00", "0.00", "0.00", "600.00")
        assertThat(components(projection, "income", "income.salary")).containsExactly("0.00", "2000.00", "0.00", "2000.00")
        assertThat(projection.income).isEqualByComparingTo("2000.00")
        assertThat(projection.expenses).isEqualByComparingTo("930.00")
        assertThat(projection.saved).isEqualByComparingTo("1070.00")
        assertThat(projection.nodes.first { it.side == "saved" }.amount).isEqualByComparingTo("1070.00")
        assertThat(projection.dayOfMonth).isEqualTo(18)
        assertThat(projection.daysInMonth).isEqualTo(31)
        assertThat(projection.monthsAvailable).isEqualTo(3)
        assertThat(listOf(projection.basisFromYear, projection.basisFromMonth, projection.basisToYear, projection.basisToMonth))
            .containsExactly(2025, 7, 2025, 9)
    }

    @Test
    fun `the actual component matches the real money flow to the cent`() {
        val (user, household) = seed()
        tx(household, user, september.atDay(5), Direction.expense, "groceries.groceries", "120.33")
        tx(household, user, october.atDay(3), Direction.income, "income.salary", "1500.10")
        tx(household, user, october.atDay(4), Direction.expense, "groceries.groceries", "45.67")
        tx(household, user, october.atDay(29), Direction.expense, "home.rent", "700.01")
        tx(household, user, october.atDay(12), Direction.expense, "groceries.groceries", "-10.00", refund = true)

        val real = service.moneyFlow(household.id, october.atDay(1), october.atEndOfMonth(), "group")
        val projection = service.moneyFlowProjection(household.id, today, "group", 3)

        for (node in real.nodes.filter { it.side == "expense" || it.side == "income" }) {
            assertThat(projection.components.first { it.side == node.side && it.id == node.id }.actual)
                .isEqualByComparingTo(node.amount)
        }
    }

    @Test
    fun `discretionary spending above the average projects exactly the actuals`() {
        val (user, household) = seed()
        tx(household, user, september.atDay(10), Direction.expense, "groceries.groceries", "300.00")
        tx(household, user, october.atDay(10), Direction.expense, "groceries.groceries", "500.00")

        val projection = service.moneyFlowProjection(household.id, today, "category", 3)

        assertThat(components(projection, "expense", "groceries.groceries")).containsExactly("500.00", "0.00", "0.00", "500.00")
    }

    @Test
    fun `every weekly occurrence the materializer has not produced yet counts as still due`() {
        val (user, household) = seed()
        // Saturday the 18th: Mondays the 20th and 27th are left; today's Saturday counts with the 25th because
        // the watermark stops at the 17th — the materializer has not run yet today.
        template(household, user, Direction.expense, "groceries.groceries", "25.00", cadence = Cadence.weekly, dayOfWeek = 1)
        template(household, user, Direction.expense, "home.rent", "10.00", cadence = Cadence.weekly, dayOfWeek = 6)

        val projection = service.moneyFlowProjection(household.id, today, "category", 3)

        assertThat(components(projection, "expense", "groceries.groceries")[1]).isEqualTo("50.00")
        assertThat(components(projection, "expense", "home.rent")[1]).isEqualTo("20.00")
    }

    @Test
    fun `with no history the projection is the recurring part alone`() {
        val (user, household) = seed()
        template(household, user, Direction.expense, "home.rent", "900.00", dayOfMonth = 28)

        val projection = service.moneyFlowProjection(household.id, today, "group", 3)

        assertThat(projection.monthsAvailable).isZero()
        assertThat(projection.basisFromYear).isNull()
        assertThat(projection.expenses).isEqualByComparingTo("900.00")
        assertThat(projection.deficit).isEqualByComparingTo("900.00")
        assertThat(projection.nodes.map { it.side }).containsExactly("deficit", "hub", "expense")
    }

    @Test
    fun `a short history averages over the months available and groups sum their categories`() {
        val (user, household) = seed()
        tx(household, user, august.atDay(3), Direction.expense, "groceries.groceries", "100.00")
        tx(household, user, september.atDay(3), Direction.expense, "groceries.groceries", "200.00")
        tx(household, user, september.atDay(4), Direction.expense, "home.rent", "60.00")

        val projection = service.moneyFlowProjection(household.id, today, "group", 3)

        // 2 of 3 months: groceries averages 150, rent 30 — never divided by the full window.
        assertThat(projection.monthsAvailable).isEqualTo(2)
        assertThat(listOf(projection.basisFromMonth, projection.basisToMonth)).containsExactly(8, 9)
        assertThat(components(projection, "expense", "groceries")).containsExactly("0.00", "0.00", "150.00", "150.00")
        assertThat(components(projection, "expense", "home")).containsExactly("0.00", "0.00", "30.00", "30.00")
    }

    private fun components(r: MoneyFlowProjectionResponse, side: String, id: String): List<String> =
        r.components.first { it.side == side && it.id == id }
            .let { listOf(it.actual, it.recurringDue, it.discretionaryEstimate, it.projected) }
            .map { it.setScale(2).toPlainString() }

    private fun template(
        h: Household,
        u: User,
        direction: Direction,
        category: String,
        amount: String,
        cadence: Cadence = Cadence.monthly,
        dayOfMonth: Short? = null,
        dayOfWeek: Short? = null,
    ): RecurringTemplate {
        val created = recurring.create(h.id, RecurringTemplateRequest(
            direction = direction,
            categoryCode = category,
            amount = BigDecimal(amount),
            description = null,
            cadence = cadence,
            dayOfMonth = dayOfMonth,
            dayOfWeek = dayOfWeek,
            startDate = july.atDay(1),
        ), u)
        return templates.save(created.apply { lastMaterializedThrough = today.minusDays(1) })
    }

    private fun tx(
        h: Household,
        u: User,
        date: LocalDate,
        direction: Direction,
        category: String,
        amount: String,
        templateId: UUID? = null,
        refund: Boolean = false,
    ) {
        transactions.save(Transaction(
            householdId = h.id,
            occurrenceDate = date,
            direction = direction,
            categoryCode = category,
            amount = BigDecimal(amount),
            description = null,
            recurringTemplateId = templateId,
            createdByUserId = u.id,
            updatedByUserId = u.id,
        ).apply { isRefund = refund })
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "proj${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }

    private companion object {
        val july: YearMonth = YearMonth.of(2025, 7)
        val august: YearMonth = YearMonth.of(2025, 8)
        val september: YearMonth = YearMonth.of(2025, 9)
    }
}
