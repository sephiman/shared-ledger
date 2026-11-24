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
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.YearMonth

class MoneyFlowMovementsTest @Autowired constructor(
    private val service: AnalyticsService,
    private val transactions: TransactionService,
    private val movements: MovementService,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
) : IntegrationTestBase() {

    private val ym = YearMonth.of(2025, 3)

    @Test
    fun `a surplus partly contributed leaves the rest in cash`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        rent(household, user, "400.00")
        movement(household, user, MovementType.contribution, "etfs", "300.00")

        val r = flow(household)

        assertBalanced(r)
        assertThat(nodeIds(r)).containsExactly("income.salary", HUB_NODE_ID, "home", CONTRIBUTED_NODE_ID, STAYS_IN_CASH_NODE_ID)
        assertThat(amountOf(r, STAYS_IN_CASH_NODE_ID)).isEqualByComparingTo("300.00")
        val movements = r.movements!!
        assertThat(movements.contributed).isEqualByComparingTo("300.00")
        assertThat(movements.staysInCash).isEqualByComparingTo("300.00")
        assertThat(movements.fromCash).isEqualByComparingTo("0.00")
    }

    @Test
    fun `contributing more than the surplus draws on cash`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        rent(household, user, "400.00")
        movement(household, user, MovementType.contribution, "etfs", "600.00")
        movement(household, user, MovementType.contribution, "crypto", "300.00")

        val r = flow(household)

        assertBalanced(r)
        assertThat(nodeIds(r)).containsExactly(
            "income.salary", FROM_CASH_NODE_ID, HUB_NODE_ID, "home", CONTRIBUTED_NODE_ID,
        )
        assertThat(amountOf(r, CONTRIBUTED_NODE_ID)).isEqualByComparingTo("900.00")
        assertThat(amountOf(r, FROM_CASH_NODE_ID)).isEqualByComparingTo("300.00")
        assertThat(r.movements!!.fromCash).isEqualByComparingTo("300.00")
    }

    @Test
    fun `a deficit covered by a withdrawal replaces the deficit node`() {
        val (user, household) = seed()
        income(household, user, "100.00")
        rent(household, user, "700.00")
        movement(household, user, MovementType.withdrawal, "crypto", "800.00")

        val r = flow(household)

        assertBalanced(r)
        assertThat(nodeIds(r)).containsExactly("income.salary", WITHDRAWN_NODE_ID, HUB_NODE_ID, "home", STAYS_IN_CASH_NODE_ID)
        assertThat(amountOf(r, STAYS_IN_CASH_NODE_ID)).isEqualByComparingTo("200.00")
        // The period still ran a deficit: a withdrawal is not income.
        assertThat(r.deficit).isEqualByComparingTo("600.00")
        assertThat(r.saved).isEqualByComparingTo("0.00")
    }

    @Test
    fun `with no movements the remainder stays in cash`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        rent(household, user, "400.00")

        val r = flow(household)

        assertBalanced(r)
        assertThat(nodeIds(r)).containsExactly("income.salary", HUB_NODE_ID, "home", STAYS_IN_CASH_NODE_ID)
        val movements = r.movements!!
        assertThat(movements.classes).isEmpty()
        assertThat(movements.contributed).isEqualByComparingTo("0.00")
        assertThat(movements.withdrawn).isEqualByComparingTo("0.00")
    }

    @Test
    fun `by category each class is its own node and by group one node per direction, with the same totals`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        rent(household, user, "400.00")
        movement(household, user, MovementType.contribution, "etfs", "500.00")
        movement(household, user, MovementType.contribution, "crypto", "150.00")
        movement(household, user, MovementType.withdrawal, "crypto", "80.00")
        movement(household, user, MovementType.withdrawal, "fund", "20.00")

        val byCategory = flow(household, "category")
        val byGroup = flow(household, "group")

        assertBalanced(byCategory)
        assertBalanced(byGroup)
        assertThat(nodeIds(byCategory)).containsExactly(
            "income.salary", "withdrawal:crypto", "withdrawal:fund", HUB_NODE_ID, "home.rent",
            "contribution:etfs", "contribution:crypto", STAYS_IN_CASH_NODE_ID,
        )
        assertThat(nodeIds(byGroup)).containsExactly(
            "income.salary", WITHDRAWN_NODE_ID, HUB_NODE_ID, "home", CONTRIBUTED_NODE_ID, STAYS_IN_CASH_NODE_ID,
        )
        assertThat(amountOf(byGroup, CONTRIBUTED_NODE_ID))
            .isEqualByComparingTo(byCategory.nodes.filter { it.side == "contribution" }.sumOf { it.amount })
            .isEqualByComparingTo("650.00")
        assertThat(amountOf(byGroup, WITHDRAWN_NODE_ID))
            .isEqualByComparingTo(byCategory.nodes.filter { it.side == "withdrawal" }.sumOf { it.amount })
            .isEqualByComparingTo("100.00")
        assertThat(amountOf(byGroup, HUB_NODE_ID)).isEqualByComparingTo(amountOf(byCategory, HUB_NODE_ID))
        // The totals line reads the same figures in both breakdowns, and the per-class detail is there in both.
        assertThat(listOf(byGroup.income, byGroup.expenses, byGroup.saved, byGroup.deficit))
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(byCategory.income, byCategory.expenses, byCategory.saved, byCategory.deficit)
        assertThat(byGroup.movements).isEqualTo(byCategory.movements)
        assertThat(byGroup.movements!!.classes.map { it.nodeId })
            .containsExactlyInAnyOrder("contribution:etfs", "contribution:crypto", "withdrawal:crypto", "withdrawal:fund")
    }

    @Test
    fun `a period with only movements still draws them`() {
        val (user, household) = seed()
        movement(household, user, MovementType.contribution, "etfs", "250.00")

        val r = flow(household)

        assertBalanced(r)
        assertThat(nodeIds(r)).containsExactly(FROM_CASH_NODE_ID, HUB_NODE_ID, CONTRIBUTED_NODE_ID)
    }

    @Test
    fun `a group refunds turned net negative is a source with contributions and withdrawals drawn too`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        rent(household, user, "400.00")
        transactions.create(household.id, TransactionRequest(
            occurrenceDate = ym.atDay(6), direction = Direction.expense, categoryCode = "outings.restaurants",
            amount = BigDecimal("-50.00"), description = null, isRefund = true,
        ), user)
        movement(household, user, MovementType.contribution, "etfs", "500.00")
        movement(household, user, MovementType.withdrawal, "crypto", "100.00")

        val r = flow(household)

        assertBalanced(r)
        assertThat(nodeIds(r)).containsExactly(
            "income.salary", "net_refund:outings", WITHDRAWN_NODE_ID, HUB_NODE_ID, "home", CONTRIBUTED_NODE_ID, STAYS_IN_CASH_NODE_ID,
        )
        // income 1000 + withdrawn 100 − expenses 350 − contributed 500
        assertThat(r.movements!!.staysInCash).isEqualByComparingTo("250.00")
    }

    @Test
    fun `movements on the cash class and debt payments are not drawn as contributions or withdrawals`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        movement(household, user, MovementType.contribution, "cash", "500.00")
        movement(household, user, MovementType.withdrawal, "cash", "200.00")
        val loan = liabilities.save(Liability(householdId = household.id, name = "Loan", createdByUserId = user.id, updatedByUserId = user.id))
        movements.create(household.id, MovementRequest(
            movementDate = ym.atDay(9), type = MovementType.debt_payment, liabilityId = loan.id, amount = BigDecimal("150.00"),
        ), user)

        val r = flow(household)

        assertBalanced(r)
        val movements = r.movements!!
        assertThat(movements.classes).isEmpty()
        assertThat(movements.contributed).isEqualByComparingTo("0.00")
        assertThat(movements.withdrawn).isEqualByComparingTo("0.00")
        assertThat(nodeIds(r)).containsExactly("income.salary", HUB_NODE_ID, STAYS_IN_CASH_NODE_ID)
    }

    @Test
    fun `income expenses saved and the savings rate are unchanged with the option on`() {
        val (user, household) = seed()
        income(household, user, "1000.00")
        rent(household, user, "400.00")
        movement(household, user, MovementType.contribution, "etfs", "900.00")
        movement(household, user, MovementType.withdrawal, "crypto", "50.00")

        val off = service.moneyFlow(household.id, ym.atDay(1), ym.atEndOfMonth(), "group")
        val on = flow(household)

        assertThat(on.income).isEqualByComparingTo(off.income)
        assertThat(on.expenses).isEqualByComparingTo(off.expenses)
        assertThat(on.saved).isEqualByComparingTo(off.saved).isEqualByComparingTo("600.00")
        assertThat(on.deficit).isEqualByComparingTo(off.deficit)
        assertThat(off.movements).isNull()
        assertThat(service.monthDashboard(household.id, ym.year, ym.monthValue).savingsRate).isEqualTo(60.0)
    }

    @Test
    fun `a class lists its top descriptions and groups the undescribed movements`() {
        val (user, household) = seed()
        income(household, user, "5000.00")
        movement(household, user, MovementType.contribution, "etfs", "100.00", "VWCE")
        movement(household, user, MovementType.contribution, "etfs", "150.00", "VWCE")
        movement(household, user, MovementType.contribution, "etfs", "200.00", "IWDA")
        movement(household, user, MovementType.contribution, "etfs", "50.00", "EIMI")
        movement(household, user, MovementType.contribution, "etfs", "20.00", "ZPRV")
        movement(household, user, MovementType.contribution, "etfs", "30.00", null)
        movement(household, user, MovementType.contribution, "etfs", "40.00", "  ")

        val etfs = flow(household).movements!!.classes.single()

        assertThat(etfs.nodeId).isEqualTo("contribution:etfs")
        assertThat(etfs.amount).isEqualByComparingTo("590.00")
        assertThat(etfs.movementCount).isEqualTo(7)
        assertThat(etfs.descriptions.map { it.description }).containsExactly("VWCE", "IWDA", "EIMI")
        assertThat(etfs.descriptions.first().amount).isEqualByComparingTo("250.00")
        assertThat(etfs.descriptions.first().count).isEqualTo(2)
        assertThat(etfs.moreDescriptions).isEqualTo(1)
        assertThat(etfs.undescribedCount).isEqualTo(2)
        assertThat(etfs.undescribedAmount).isEqualByComparingTo("70.00")
    }

    private fun flow(household: Household, level: String = "group") =
        service.moneyFlow(household.id, ym.atDay(1), ym.atEndOfMonth(), level, includeMovements = true)

    /** Every node's amount equals its link, and the hub's inflow equals its outflow, to the cent. */
    private fun assertBalanced(r: MoneyFlowResponse) {
        val inflow = r.links.filter { it.target == HUB_NODE_ID }.sumOf { it.amount }
        val outflow = r.links.filter { it.source == HUB_NODE_ID }.sumOf { it.amount }
        assertThat(inflow).isEqualByComparingTo(outflow)
        assertThat(amountOf(r, HUB_NODE_ID)).isEqualByComparingTo(inflow)
        for (node in r.nodes.filter { it.side != "hub" }) {
            val link = r.links.single { it.source == node.id || it.target == node.id }
            assertThat(link.amount).`as`(node.id).isEqualByComparingTo(node.amount)
            assertThat(node.amount.signum()).`as`(node.id).isPositive()
        }
        assertThat(r.nodes.map { it.side }).doesNotContain("saved", "deficit")
    }

    private fun nodeIds(r: MoneyFlowResponse) = r.nodes.map { it.id }

    private fun amountOf(r: MoneyFlowResponse, id: String) = r.nodes.single { it.id == id }.amount

    private fun income(h: Household, u: User, amount: String) = tx(h, u, Direction.income, "income.salary", amount)

    private fun rent(h: Household, u: User, amount: String) = tx(h, u, Direction.expense, "home.rent", amount)

    private fun tx(h: Household, u: User, direction: Direction, category: String, amount: String) {
        transactions.create(h.id, TransactionRequest(
            occurrenceDate = ym.atDay(5), direction = direction, categoryCode = category, amount = BigDecimal(amount), description = null,
        ), u)
    }

    private fun movement(h: Household, u: User, type: MovementType, assetClass: String, amount: String, description: String? = null) {
        movements.create(h.id, MovementRequest(
            movementDate = ym.atDay(10), type = type, assetClassCode = assetClass, amount = BigDecimal(amount), description = description,
        ), u)
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "mfi${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
