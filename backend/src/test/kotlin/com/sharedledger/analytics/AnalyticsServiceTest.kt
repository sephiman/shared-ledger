package com.sharedledger.analytics

import com.sharedledger.IntegrationTestBase
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import com.sharedledger.networth.liability.Liability
import com.sharedledger.networth.liability.LiabilityRepository
import com.sharedledger.networth.movement.MovementRequest
import com.sharedledger.networth.movement.MovementService
import com.sharedledger.networth.movement.MovementType
import com.sharedledger.networth.snapshot.AssetValueInput
import com.sharedledger.networth.snapshot.SnapshotRequest
import com.sharedledger.networth.snapshot.SnapshotService
import com.sharedledger.recurring.Cadence
import com.sharedledger.recurring.RecurringService
import com.sharedledger.recurring.RecurringTemplateRequest
import com.sharedledger.transaction.Direction
import com.sharedledger.transaction.Transaction
import com.sharedledger.transaction.TransactionRepository
import com.sharedledger.transaction.TransactionRequest
import com.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class AnalyticsServiceTest @Autowired constructor(
    private val service: AnalyticsService,
    private val transactions: TransactionService,
    private val transactionsRepo: TransactionRepository,
    private val recurring: RecurringService,
    private val movements: MovementService,
    private val snapshots: SnapshotService,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
) : IntegrationTestBase() {

    @Test
    fun `allocation reports income expenses and saved`() {
        val (user, household) = seed()
        val ym = YearMonth.of(2025, 3)
        addTx(household, user, ym.atDay(5), Direction.income, "income.salary", "1000.00")
        addTx(household, user, ym.atDay(10), Direction.expense, "groceries.groceries", "200.00")
        addTx(household, user, ym.atDay(15), Direction.expense, "home.rent", "300.00")

        val r = service.allocation(household.id, ym.year, ym.monthValue)
        assertThat(r.income).isEqualByComparingTo("1000.00")
        assertThat(r.expenses).isEqualByComparingTo("500.00")
        assertThat(r.saved).isEqualByComparingTo("500.00")
        val groceriesSlice = r.slices.first { it.groupCode == "groceries" }
        assertThat(groceriesSlice.amount).isEqualByComparingTo("200.00")
        assertThat(groceriesSlice.percentOfIncome).isEqualTo(20.0)
    }

    @Test
    fun `allocation with zero income produces no saved slice`() {
        val (user, household) = seed()
        val ym = YearMonth.of(2025, 4)
        addTx(household, user, ym.atDay(2), Direction.expense, "groceries.groceries", "50.00")
        val r = service.allocation(household.id, ym.year, ym.monthValue)
        assertThat(r.income).isEqualByComparingTo("0.00")
        assertThat(r.saved).isEqualByComparingTo("0.00")
        assertThat(r.slices.first { it.groupCode == "groceries" }.percentOfIncome).isEqualTo(0.0)
    }

    @Test
    fun `recurring share splits by template reference`() {
        val (user, household) = seed()
        val template = recurring.create(household.id, RecurringTemplateRequest(
            direction = Direction.expense,
            categoryCode = "home.rent",
            amount = BigDecimal("800.00"),
            description = null,
            cadence = Cadence.monthly,
            dayOfMonth = 1,
            startDate = LocalDate.of(2025, 1, 1),
        ), user)
        val ym = YearMonth.of(2025, 5)
        addTxWithTemplate(household, user, ym.atDay(1), "home.rent", "800.00", template.id)
        addTx(household, user, ym.atDay(10), Direction.expense, "groceries.groceries", "200.00")

        val r = service.recurringShare(household.id, "month", ym.year, ym.monthValue)
        assertThat(r.recurring).isEqualByComparingTo("800.00")
        assertThat(r.discretionary).isEqualByComparingTo("200.00")
        assertThat(r.total).isEqualByComparingTo("1000.00")
        assertThat(r.recurringShare).isEqualTo(80.0)
    }

    @Test
    fun `top movers ranks increases and decreases against year-ago baseline`() {
        val (user, household) = seed()
        val period = YearMonth.of(2025, 3)
        val baseline = period.minusYears(1)
        addTx(household, user, baseline.atDay(5), Direction.expense, "groceries.groceries", "100.00")
        addTx(household, user, period.atDay(5), Direction.expense, "groceries.groceries", "180.00")
        addTx(household, user, baseline.atDay(6), Direction.expense, "outings.restaurants", "200.00")
        addTx(household, user, period.atDay(6), Direction.expense, "outings.restaurants", "50.00")
        addTx(household, user, period.atDay(7), Direction.expense, "shopping.other", "30.00")

        val r = service.topMovers(household.id, period.year, period.monthValue, "year_ago")
        assertThat(r.increases.map { it.categoryCode }).contains("groceries.groceries")
        assertThat(r.decreases.map { it.categoryCode }).contains("outings.restaurants")
        assertThat(r.newActivity.map { it.categoryCode }).contains("shopping.other")
    }

    @Test
    fun `dashboard extras fixed cost uses 12 month average`() {
        val (user, household) = seed()
        val asOf = YearMonth.of(2025, 6)
        val template = recurring.create(household.id, RecurringTemplateRequest(
            direction = Direction.expense,
            categoryCode = "home.rent",
            amount = BigDecimal("1000.00"),
            description = null,
            cadence = Cadence.monthly,
            dayOfMonth = 1,
            startDate = LocalDate.of(2024, 7, 1),
        ), user)
        var cursor = asOf.minusMonths(11)
        while (!cursor.isAfter(asOf)) {
            addTxWithTemplate(household, user, cursor.atDay(1), "home.rent", "1000.00", template.id)
            addTx(household, user, cursor.atDay(10), Direction.expense, "groceries.groceries", "200.00")
            cursor = cursor.plusMonths(1)
        }

        val r = service.dashboardExtras(household.id, asOf)
        assertThat(r.monthsAvailable).isEqualTo(12)
        assertThat(r.fixedRecurring.monthlyAverage).isEqualByComparingTo("1000.00")
        assertThat(r.fixedAll.monthlyAverage).isEqualByComparingTo("1200.00")
        assertThat(r.sparkline).hasSize(24)
    }

    @Test
    fun `heatmap returns null for empty months and value for filled months`() {
        val (user, household) = seed()
        val ym = YearMonth.now()
        addTx(household, user, ym.atDay(5), Direction.expense, "groceries.groceries", "100.00")

        val r = service.heatmap(household.id, 24, Direction.expense)
        assertThat(r.months).hasSize(24)
        val grocRow = r.categories.first { it.categoryCode == "groceries.groceries" }
        val last = grocRow.values.last()
        assertThat(last).isNotNull
        assertThat(last!!).isEqualByComparingTo("100.00")
        assertThat(grocRow.values.dropLast(1).all { it == null }).isTrue()
    }

    @Test
    fun `contribution series is empty when no movements exist`() {
        val (user, household) = seed()
        snapshots.create(household.id, SnapshotRequest(
            snapshotDate = LocalDate.of(2025, 1, 31),
            note = null,
            assets = listOf(AssetValueInput("cash", BigDecimal("100.00"))),
            liabilities = emptyList(),
            confirmLargeChanges = false,
        ), user)
        val r = service.contributionSeries(household.id)
        assertThat(r.hasMovements).isFalse()
        assertThat(r.points).isEmpty()
    }

    @Test
    fun `contribution series accumulates through each snapshot date and ignores debt payments`() {
        val (user, household) = seed()
        snapshots.create(household.id, SnapshotRequest(
            snapshotDate = LocalDate.of(2025, 1, 31),
            note = null,
            assets = listOf(AssetValueInput("cash", BigDecimal("1000.00"))),
            liabilities = emptyList(),
            confirmLargeChanges = false,
        ), user)
        movements.create(household.id, MovementRequest(
            movementDate = LocalDate.of(2025, 2, 10),
            type = MovementType.contribution,
            assetClassCode = "etfs",
            liabilityId = null,
            amount = BigDecimal("500.00"),
            description = null,
        ), user)
        val liability = liabilities.save(Liability(
            householdId = household.id,
            name = "Loan-${System.nanoTime()}",
            active = true,
            createdByUserId = user.id,
            updatedByUserId = user.id,
        ))
        movements.create(household.id, MovementRequest(
            movementDate = LocalDate.of(2025, 2, 15),
            type = MovementType.debt_payment,
            assetClassCode = null,
            liabilityId = liability.id,
            amount = BigDecimal("100.00"),
            description = null,
        ), user)
        snapshots.create(household.id, SnapshotRequest(
            snapshotDate = LocalDate.of(2025, 2, 28),
            note = null,
            assets = listOf(AssetValueInput("cash", BigDecimal("500.00")), AssetValueInput("etfs", BigDecimal("550.00"))),
            liabilities = emptyList(),
            confirmLargeChanges = false,
        ), user)

        val r = service.contributionSeries(household.id)
        assertThat(r.hasMovements).isTrue()
        assertThat(r.points).hasSize(2)
        assertThat(r.points[0].netContribution).isEqualByComparingTo("0.00")
        assertThat(r.points[1].netContribution).isEqualByComparingTo("500.00")
    }

    private fun addTx(h: Household, u: User, date: LocalDate, dir: Direction, cat: String, amount: String) {
        transactions.create(h.id, TransactionRequest(
            occurrenceDate = date,
            direction = dir,
            categoryCode = cat,
            amount = BigDecimal(amount),
            description = null,
        ), u)
    }

    private fun addTxWithTemplate(h: Household, u: User, date: LocalDate, cat: String, amount: String, templateId: UUID) {
        transactionsRepo.save(Transaction(
            householdId = h.id,
            occurrenceDate = date,
            direction = Direction.expense,
            categoryCode = cat,
            amount = BigDecimal(amount),
            description = null,
            recurringTemplateId = templateId,
            createdByUserId = u.id,
            updatedByUserId = u.id,
        ))
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "an${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
