package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.movement.MovementRequest
import com.sephilabs.sharedledger.networth.movement.MovementService
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.snapshot.AssetValueInput
import com.sephilabs.sharedledger.networth.snapshot.LiabilityBalanceInput
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRequest
import com.sephilabs.sharedledger.networth.snapshot.SnapshotService
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringService
import com.sephilabs.sharedledger.recurring.RecurringTemplateRequest
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
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
    fun `money flow builds income to hub to group links with a saved node`() {
        val (user, household) = seed()
        val ym = YearMonth.of(2025, 3)
        addTx(household, user, ym.atDay(5), Direction.income, "income.salary", "1000.00")
        addTx(household, user, ym.atDay(6), Direction.income, "income.financial", "100.00")
        addTx(household, user, ym.atDay(10), Direction.expense, "groceries.groceries", "200.00")
        addTx(household, user, ym.atDay(15), Direction.expense, "home.rent", "300.00")

        val r = service.moneyFlow(household.id, ym.atDay(1), ym.atEndOfMonth(), "group")
        assertThat(r.income).isEqualByComparingTo("1100.00")
        assertThat(r.expenses).isEqualByComparingTo("500.00")
        assertThat(r.saved).isEqualByComparingTo("600.00")
        assertThat(r.deficit).isEqualByComparingTo("0.00")

        assertThat(r.nodes.map { it.id }).containsExactly(
            "income.salary", "income.financial", HUB_NODE_ID, "home", "groceries", SAVED_NODE_ID,
        )
        assertThat(r.nodes.first { it.id == HUB_NODE_ID }.amount).isEqualByComparingTo("1100.00")
        assertThat(r.nodes.first { it.id == "home" }.groupCode).isEqualTo("home")
        assertThat(r.links).hasSize(5)
        assertThat(r.links.first { it.source == "income.salary" }.target).isEqualTo(HUB_NODE_ID)
        assertThat(r.links.first { it.target == SAVED_NODE_ID }.amount).isEqualByComparingTo("600.00")
        // Inflows and outflows of the hub balance exactly.
        val inflow = r.links.filter { it.target == HUB_NODE_ID }.sumOf { it.amount }
        val outflow = r.links.filter { it.source == HUB_NODE_ID }.sumOf { it.amount }
        assertThat(inflow).isEqualByComparingTo(outflow)
    }

    @Test
    fun `money flow adds a deficit node feeding the hub when expenses exceed income`() {
        val (user, household) = seed()
        val ym = YearMonth.of(2025, 4)
        addTx(household, user, ym.atDay(5), Direction.income, "income.salary", "100.00")
        addTx(household, user, ym.atDay(10), Direction.expense, "home.rent", "700.00")

        val r = service.moneyFlow(household.id, ym.atDay(1), ym.atEndOfMonth(), "group")
        assertThat(r.saved).isEqualByComparingTo("0.00")
        assertThat(r.deficit).isEqualByComparingTo("600.00")
        assertThat(r.nodes.map { it.id }).containsExactly("income.salary", DEFICIT_NODE_ID, HUB_NODE_ID, "home")
        assertThat(r.links.first { it.source == DEFICIT_NODE_ID }.target).isEqualTo(HUB_NODE_ID)
        assertThat(r.links.first { it.source == DEFICIT_NODE_ID }.amount).isEqualByComparingTo("600.00")
        assertThat(r.nodes.first { it.id == HUB_NODE_ID }.amount).isEqualByComparingTo("700.00")
        assertThat(r.nodes.map { it.id }).doesNotContain(SAVED_NODE_ID)
    }

    @Test
    fun `money flow at category level keeps leaf categories with their group code`() {
        val (user, household) = seed()
        val ym = YearMonth.of(2025, 5)
        addTx(household, user, ym.atDay(5), Direction.income, "income.salary", "1000.00")
        addTx(household, user, ym.atDay(10), Direction.expense, "home.rent", "300.00")
        addTx(household, user, ym.atDay(11), Direction.expense, "home.utilities", "100.00")

        val r = service.moneyFlow(household.id, ym.atDay(1), ym.atEndOfMonth(), "category")
        val expenseNodes = r.nodes.filter { it.side == "expense" }
        assertThat(expenseNodes.map { it.id }).containsExactly("home.rent", "home.utilities")
        assertThat(expenseNodes).allMatch { it.groupCode == "home" }
    }

    @Test
    fun `money flow group totals match allocation for the same period`() {
        val (user, household) = seed()
        val ym = YearMonth.of(2025, 6)
        addTx(household, user, ym.atDay(1), Direction.income, "income.salary", "2345.67")
        addTx(household, user, ym.atDay(3), Direction.expense, "home.rent", "876.54")
        addTx(household, user, ym.atDay(8), Direction.expense, "groceries.groceries", "123.45")
        addTx(household, user, ym.atDay(9), Direction.expense, "outings.restaurants", "67.89")

        val allocation = service.allocation(household.id, ym.year, ym.monthValue)
        val flow = service.moneyFlow(household.id, ym.atDay(1), ym.atEndOfMonth(), "group")

        assertThat(flow.income).isEqualByComparingTo(allocation.income)
        assertThat(flow.expenses).isEqualByComparingTo(allocation.expenses)
        assertThat(flow.saved).isEqualByComparingTo(allocation.saved)
        for (slice in allocation.slices) {
            assertThat(flow.nodes.first { it.id == slice.groupCode }.amount).isEqualByComparingTo(slice.amount)
        }
    }

    @Test
    fun `money flow returns no nodes for an empty period`() {
        val (_, household) = seed()
        val r = service.moneyFlow(household.id, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), "group")
        assertThat(r.income).isEqualByComparingTo("0.00")
        assertThat(r.expenses).isEqualByComparingTo("0.00")
        assertThat(r.nodes).isEmpty()
        assertThat(r.links).isEmpty()
    }

    @Test
    fun `trailing12 reports net savings per month and range averages`() {
        val (user, household) = seed()
        val asOf = YearMonth.of(2025, 6)
        // asOf month: income 2000, expenses 500 -> net 1500
        addTx(household, user, asOf.atDay(5), Direction.income, "income.salary", "2000.00")
        addTx(household, user, asOf.atDay(10), Direction.expense, "home.rent", "500.00")
        // one month earlier: income 1000, expenses 1200 -> net -200
        val prev = asOf.minusMonths(1)
        addTx(household, user, prev.atDay(5), Direction.income, "income.salary", "1000.00")
        addTx(household, user, prev.atDay(10), Direction.expense, "home.rent", "1200.00")

        val r = service.trailing12(household.id, asOf)

        assertThat(r.points).hasSize(12)
        val last = r.points.last()
        assertThat(last.year).isEqualTo(asOf.year)
        assertThat(last.month).isEqualTo(asOf.monthValue)
        assertThat(last.netSavings).isEqualByComparingTo("1500.00")
        val prevPoint = r.points.first { it.year == prev.year && it.month == prev.monthValue }
        assertThat(prevPoint.netSavings).isEqualByComparingTo("-200.00")

        // Averages divide by 12 months (the 10 empty months count toward the denominator).
        assertThat(r.summary.avgIncome).isEqualByComparingTo("250.00") // 3000 / 12
        assertThat(r.summary.avgExpenses).isEqualByComparingTo("141.67") // 1700 / 12, HALF_EVEN
        assertThat(r.summary.avgNetSavings).isEqualByComparingTo("108.33") // 1300 / 12
        // 10 zero months + (-200) + 1500 -> sorted median of 12 values is 0
        assertThat(r.summary.medianNetSavings).isEqualByComparingTo("0.00")
    }

    @Test
    fun `trailing12 with no transactions yields zero averages without dividing by zero`() {
        val (_, household) = seed()
        val r = service.trailing12(household.id, YearMonth.of(2025, 6))

        assertThat(r.points).hasSize(12)
        assertThat(r.points).allMatch { it.netSavings.signum() == 0 }
        assertThat(r.summary.avgIncome).isEqualByComparingTo("0.00")
        assertThat(r.summary.avgExpenses).isEqualByComparingTo("0.00")
        assertThat(r.summary.avgNetSavings).isEqualByComparingTo("0.00")
        assertThat(r.summary.medianNetSavings).isEqualByComparingTo("0.00")
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
    fun `cost of living separates essential from discretionary`() {
        val (user, household) = seed()
        val asOf = YearMonth.of(2025, 6)
        var cursor = asOf.minusMonths(11)
        while (!cursor.isAfter(asOf)) {
            addTx(household, user, cursor.atDay(1), Direction.expense, "home.rent", "1000.00")
            addTx(household, user, cursor.atDay(5), Direction.expense, "groceries.groceries", "300.00")
            addTx(household, user, cursor.atDay(10), Direction.expense, "outings.restaurants", "200.00")
            addTx(household, user, cursor.atDay(15), Direction.expense, "shopping.clothing", "100.00")
            cursor = cursor.plusMonths(1)
        }

        val r = service.costOfLiving(household.id, asOf)
        assertThat(r.monthsAvailable).isEqualTo(12)
        assertThat(r.essentialMonthlyAverage).isEqualByComparingTo("1300.00")
        assertThat(r.nonEssentialMonthlyAverage).isEqualByComparingTo("300.00")
        assertThat(r.totalMonthlyAverage).isEqualByComparingTo("1600.00")
        assertThat(r.essentialPerYear).isEqualByComparingTo("15600.00")
        assertThat(r.nonEssentialPerYear).isEqualByComparingTo("3600.00")
        assertThat(r.totalPerYear).isEqualByComparingTo("19200.00")
        assertThat(r.essentialShare).isEqualTo(81.25)
        assertThat(r.essentialCategories.map { it.categoryCode }).containsExactly("home.rent", "groceries.groceries")
        assertThat(r.nonEssentialCategories.map { it.categoryCode }).containsExactly("outings.restaurants", "shopping.clothing")
    }

    @Test
    fun `cost of living divides by months available not by twelve`() {
        val (user, household) = seed()
        val asOf = YearMonth.of(2025, 3)
        addTx(household, user, YearMonth.of(2025, 1).atDay(1), Direction.expense, "home.rent", "1000.00")
        addTx(household, user, YearMonth.of(2025, 2).atDay(1), Direction.expense, "home.rent", "1000.00")
        addTx(household, user, YearMonth.of(2025, 3).atDay(1), Direction.expense, "home.rent", "1000.00")

        val r = service.costOfLiving(household.id, asOf)
        assertThat(r.monthsAvailable).isEqualTo(3)
        assertThat(r.essentialMonthlyAverage).isEqualByComparingTo("1000.00")
        assertThat(r.totalMonthlyAverage).isEqualByComparingTo("1000.00")
    }

    @Test
    fun `cost of living ignores income rows`() {
        val (user, household) = seed()
        val asOf = YearMonth.of(2025, 6)
        addTx(household, user, asOf.atDay(1), Direction.income, "income.salary", "5000.00")
        addTx(household, user, asOf.atDay(5), Direction.expense, "home.rent", "1000.00")

        val r = service.costOfLiving(household.id, asOf)
        assertThat(r.essentialMonthlyAverage).isEqualByComparingTo("1000.00")
        assertThat(r.totalMonthlyAverage).isEqualByComparingTo("1000.00")
        assertThat(r.essentialShare).isEqualTo(100.0)
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
    fun `daily aggregates expenses by day and excludes income`() {
        val (user, household) = seed()
        val d1 = LocalDate.of(2025, 5, 10)
        val d2 = LocalDate.of(2025, 5, 12)
        addTx(household, user, d1, Direction.expense, "groceries.groceries", "30.00")
        addTx(household, user, d1, Direction.expense, "outings.restaurants", "20.00")
        addTx(household, user, d2, Direction.expense, "shopping.other", "40.00")
        addTx(household, user, d1, Direction.income, "income.salary", "1000.00")

        val r = service.daily(household.id, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31), Direction.expense)
        assertThat(r.days).hasSize(2)
        assertThat(r.days[0].date).isEqualTo(d1)
        assertThat(r.days[0].amount).isEqualByComparingTo("50.00")
        assertThat(r.days[1].date).isEqualTo(d2)
        assertThat(r.days[1].amount).isEqualByComparingTo("40.00")
    }

    @Test
    fun `daily returns only non-zero days`() {
        val (user, household) = seed()
        addTx(household, user, LocalDate.of(2025, 5, 10), Direction.expense, "groceries.groceries", "30.00")
        addTx(household, user, LocalDate.of(2025, 5, 15), Direction.income, "income.salary", "500.00")

        val r = service.daily(household.id, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31), Direction.expense)
        assertThat(r.days).hasSize(1)
        assertThat(r.days[0].date).isEqualTo(LocalDate.of(2025, 5, 10))
    }

    @Test
    fun `daily honors direction income`() {
        val (user, household) = seed()
        addTx(household, user, LocalDate.of(2025, 5, 10), Direction.expense, "groceries.groceries", "30.00")
        addTx(household, user, LocalDate.of(2025, 5, 15), Direction.income, "income.salary", "500.00")

        val r = service.daily(household.id, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31), Direction.income)
        assertThat(r.days).hasSize(1)
        assertThat(r.days[0].date).isEqualTo(LocalDate.of(2025, 5, 15))
        assertThat(r.days[0].amount).isEqualByComparingTo("500.00")
    }

    @Test
    fun `daily sorts ascending by date`() {
        val (user, household) = seed()
        addTx(household, user, LocalDate.of(2025, 5, 20), Direction.expense, "groceries.groceries", "10.00")
        addTx(household, user, LocalDate.of(2025, 5, 3), Direction.expense, "groceries.groceries", "20.00")
        addTx(household, user, LocalDate.of(2025, 5, 12), Direction.expense, "groceries.groceries", "30.00")

        val r = service.daily(household.id, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31), Direction.expense)
        assertThat(r.days.map { it.date }).containsExactly(
            LocalDate.of(2025, 5, 3),
            LocalDate.of(2025, 5, 12),
            LocalDate.of(2025, 5, 20),
        )
    }

    @Test
    fun `daily clips strictly to from to range inclusive`() {
        val (user, household) = seed()
        addTx(household, user, LocalDate.of(2025, 4, 30), Direction.expense, "groceries.groceries", "10.00")
        addTx(household, user, LocalDate.of(2025, 5, 1), Direction.expense, "groceries.groceries", "20.00")
        addTx(household, user, LocalDate.of(2025, 5, 31), Direction.expense, "groceries.groceries", "30.00")
        addTx(household, user, LocalDate.of(2025, 6, 1), Direction.expense, "groceries.groceries", "40.00")

        val r = service.daily(household.id, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31), Direction.expense)
        assertThat(r.days.map { it.date }).containsExactly(
            LocalDate.of(2025, 5, 1),
            LocalDate.of(2025, 5, 31),
        )
        assertThat(r.from).isEqualTo(LocalDate.of(2025, 5, 1))
        assertThat(r.to).isEqualTo(LocalDate.of(2025, 5, 31))
        assertThat(r.direction).isEqualTo("expense")
    }

    @Test
    fun `contribution series is empty when no movements exist`() {
        val (user, household) = seed()
        snapshots.create(household.id, SnapshotRequest(
            snapshotDate = LocalDate.of(2025, 1, 31),
            note = null,
            assets = fullAssetSet("cash" to "100.00"),
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
            assets = fullAssetSet("cash" to "1000.00"),
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
            assets = fullAssetSet("cash" to "500.00", "etfs" to "550.00"),
            liabilities = listOf(LiabilityBalanceInput(liability.id, BigDecimal("0.00"))),
            confirmLargeChanges = true,
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

    /**
     * SnapshotService.create rejects a request that omits any asset class from the catalog,
     * so tests must include all six (cash, fund, etfs, stocks, crypto, pension).
     * Pass the codes/values that matter for the test; the rest default to 0.
     */
    private fun fullAssetSet(vararg values: Pair<String, String>): List<AssetValueInput> {
        val overrides = values.toMap()
        return listOf("cash", "fund", "etfs", "stocks", "crypto", "pension").map { code ->
            AssetValueInput(code, BigDecimal(overrides[code] ?: "0.00"))
        }
    }
}
