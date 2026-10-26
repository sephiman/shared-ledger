package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.analytics.projectionmode.GroupProjectionModeService
import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringService
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

class MoneyFlowProjectionModeTest @Autowired constructor(
    private val service: AnalyticsService,
    private val modes: GroupProjectionModeService,
    private val transactions: TransactionRepository,
    private val recurring: RecurringService,
    private val templates: RecurringTemplateRepository,
    private val users: UserRepository,
    private val households: HouseholdRepository,
) : IntegrationTestBase() {

    private val today = LocalDate.of(2025, 10, 18)
    private val october = YearMonth.from(today)

    @Test
    fun `explicit envelope rows project exactly what no stored modes project`() {
        val defaults = seededHousehold()
        val explicit = seededHousehold()
        for (group in modes.list(explicit.second.id).map { it.groupCode }) {
            modes.set(explicit.second.id, group, ProjectionMode.ENVELOPE)
        }

        for (level in listOf("group", "category")) {
            val expected = service.moneyFlowProjection(defaults.second.id, today, level, 3)
            val actual = service.moneyFlowProjection(explicit.second.id, today, level, 3)
            assertThat(actual).usingRecursiveComparison().isEqualTo(expected)
        }
    }

    @Test
    fun `a prorated group expects its average over the days still ahead`() {
        val (_, household) = seededHousehold()
        modes.set(household.id, "groceries", ProjectionMode.PRORATE)

        val projection = service.moneyFlowProjection(household.id, today, "group", 3)

        // 330 average × 13/31 days left = 138.387…, regardless of the 200 already spent.
        assertThat(components(projection, "expense", "groceries")).containsExactly("200.00", "0.00", "138.39", "338.39")
        assertThat(modeOf(projection, "expense", "groceries")).isEqualTo("PRORATE")
        assertThat(components(projection, "expense", "home")).containsExactly("600.00", "0.00", "0.00", "600.00")
        assertThat(modeOf(projection, "expense", "home")).isEqualTo("ENVELOPE")
        assertThat(modeOf(projection, "income", "income.salary")).isNull()
        assertThat(projection.expenses).isEqualByComparingTo("938.39")
        assertThat(projection.saved).isEqualByComparingTo("1061.61")
        assertThat(projection.daysRemaining).isEqualTo(13)
    }

    @Test
    fun `each category inherits its group's mode and the group sums its categories`() {
        val (user, household) = seed()
        for (ym in listOf(july, august, september)) {
            tx(household, user, ym.atDay(5), Direction.expense, "outings.restaurants", "93.00")
            tx(household, user, ym.atDay(6), Direction.expense, "outings.hobbies", "31.00")
        }
        modes.set(household.id, "outings", ProjectionMode.PRORATE)

        val byCategory = service.moneyFlowProjection(household.id, today, "category", 3)
        val byGroup = service.moneyFlowProjection(household.id, today, "group", 3)

        // 93 × 13/31 = 39.00 and 31 × 13/31 = 13.00.
        assertThat(components(byCategory, "expense", "outings.restaurants")[2]).isEqualTo("39.00")
        assertThat(components(byCategory, "expense", "outings.hobbies")[2]).isEqualTo("13.00")
        assertThat(modeOf(byCategory, "expense", "outings.restaurants")).isEqualTo("PRORATE")
        assertThat(components(byGroup, "expense", "outings")[2]).isEqualTo("52.00")
    }

    @Test
    fun `recurring still due is added in full whatever the mode`() {
        val (user, household) = seed()
        template(household, user, "groceries.groceries", "25.00", dayOfWeek = 1)
        modes.set(household.id, "groceries", ProjectionMode.PRORATE)

        val projection = service.moneyFlowProjection(household.id, today, "group", 3)

        assertThat(components(projection, "expense", "groceries")).containsExactly("0.00", "50.00", "0.00", "50.00")
    }

    @Test
    fun `the forecast of future months is the same in both modes`() {
        val (user, household) = seed()
        val current = YearMonth.now()
        for (back in 1L..3L) {
            tx(household, user, current.minusMonths(back).atDay(5), Direction.expense, "groceries.groceries", "100.0$back")
            tx(household, user, current.minusMonths(back).atDay(6), Direction.expense, "home.mortgage", "731.1$back")
        }
        template(household, user, "transport.fuel", "40.00", dayOfWeek = 3)

        val envelope = service.forecast(household.id, 12, 3)
        for (group in modes.list(household.id).map { it.groupCode }) modes.set(household.id, group, ProjectionMode.PRORATE)
        val prorate = service.forecast(household.id, 12, 3)

        assertThat(prorate).usingRecursiveComparison().isEqualTo(envelope)
        assertThat(envelope.categories.first { it.categoryCode == "groceries.groceries" }.projection.first().projectedExpense)
            .isEqualByComparingTo("100.02")
    }

    private fun seededHousehold(): Pair<User, Household> {
        val (user, household) = seed()
        for ((ym, groceries) in listOf(july to "300.00", august to "330.00", september to "360.00")) {
            tx(household, user, ym.atDay(1), Direction.expense, "home.rent", "600.00")
            tx(household, user, ym.atDay(25), Direction.income, "income.salary", "2000.00")
            tx(household, user, ym.atDay(10), Direction.expense, "groceries.groceries", groceries)
        }
        tx(household, user, october.atDay(1), Direction.expense, "home.rent", "600.00")
        tx(household, user, october.atDay(9), Direction.expense, "groceries.groceries", "200.00")
        return user to household
    }

    private fun components(r: MoneyFlowProjectionResponse, side: String, id: String): List<String> =
        r.components.first { it.side == side && it.id == id }
            .let { listOf(it.actual, it.recurringDue, it.discretionaryEstimate, it.projected) }
            .map { it.setScale(2).toPlainString() }

    private fun modeOf(r: MoneyFlowProjectionResponse, side: String, id: String): String? =
        r.components.first { it.side == side && it.id == id }.projectionMode

    private fun template(h: Household, u: User, category: String, amount: String, dayOfWeek: Short) {
        val created = recurring.create(h.id, RecurringTemplateRequest(
            direction = Direction.expense,
            categoryCode = category,
            amount = BigDecimal(amount),
            description = null,
            cadence = Cadence.weekly,
            dayOfWeek = dayOfWeek,
            startDate = july.atDay(1),
        ), u)
        templates.save(created.apply { lastMaterializedThrough = today.minusDays(1) })
    }

    private fun tx(h: Household, u: User, date: LocalDate, direction: Direction, category: String, amount: String) {
        transactions.save(Transaction(
            householdId = h.id,
            occurrenceDate = date,
            direction = direction,
            categoryCode = category,
            amount = BigDecimal(amount),
            description = null,
            recurringTemplateId = null,
            createdByUserId = u.id,
            updatedByUserId = u.id,
        ))
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "mode${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }

    private companion object {
        val july: YearMonth = YearMonth.of(2025, 7)
        val august: YearMonth = YearMonth.of(2025, 8)
        val september: YearMonth = YearMonth.of(2025, 9)
    }
}
