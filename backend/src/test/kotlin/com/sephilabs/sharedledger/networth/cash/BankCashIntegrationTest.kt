package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.BankCredentialsService
import com.sephilabs.sharedledger.bank.BankService
import com.sephilabs.sharedledger.bank.BankTestKeys
import com.sephilabs.sharedledger.bank.CompleteLinkRequest
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.bank.FakeBankConnector
import com.sephilabs.sharedledger.bank.StartLinkRequest
import com.sephilabs.sharedledger.bank.connector.AuthorizedAccount
import com.sephilabs.sharedledger.bank.connector.BankBalance
import com.sephilabs.sharedledger.bank.sync.BankSyncService
import com.sephilabs.sharedledger.bank.sync.SyncMode
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Linked-bank cash: the first sync of a day reads each account's balance, and the counted balances plus
 *  the free other cash become the `bank` cash adjustment the estimate anchors on. */
@ResourceLock("fake-bank-connector")
class BankCashIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val credentialsService: BankCredentialsService,
    private val bankService: BankService,
    private val syncService: BankSyncService,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val bankCash: BankCashService,
    private val estimates: CashEstimateService,
    private val adjustments: CashAdjustmentRepository,
    private val transactions: TransactionRepository,
    private val fake: FakeBankConnector,
) : IntegrationTestBase() {

    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)

    @BeforeEach
    fun setUp() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        fake.movements.clear()
        fake.movementsByAccount.clear()
        fake.fetchCalls.clear()
        fake.scriptedPages.clear()
        fake.failWithRateLimitAfter = -1
        fake.failWithProviderErrorAfter = -1
        fake.failWithClosedSessionAfter = -1
        fake.failingAccountUids.clear()
        fake.balancesByAccount.clear()
        fake.balanceCalls.clear()
        fake.failingBalanceUids.clear()
        fake.accounts = listOf(
            AuthorizedAccount("acc-checking", "ES0000000000000000000001", "Checking", "EUR"),
            AuthorizedAccount("acc-savings", "ES0000000000000000000002", "Savings", "EUR"),
        )
        fake.balancesByAccount["acc-checking"] = listOf(
            BankBalance("ITAV", BigDecimal("1500.00"), "EUR", today),
            BankBalance("CLBD", BigDecimal("1200.00"), "EUR", yesterday),
        )
        fake.balancesByAccount["acc-savings"] = listOf(BankBalance("CLBD", BigDecimal("800.00"), "EUR", yesterday))
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `linking reads the closing balances and anchors cash on their sum dated at the reference day`() {
        val (user, household) = seed()

        link(household, user)

        val overview = bankCash.overview(household.id)
        assertThat(overview.bankTotal).isEqualByComparingTo("2000.00")
        assertThat(overview.accounts.map { it.account.balanceType }).containsOnly("CLBD")
        val anchor = bankAnchors(household.id).single()
        assertThat(anchor.adjustmentDate).isEqualTo(yesterday)
        assertThat(anchor.amount).isEqualByComparingTo("2000.00")
        assertThat(anchor.createdByUserId).isNull()
    }

    @Test
    fun `balances are read only on the first sync of the day`() {
        val (user, household) = seed()
        link(household, user)
        assertThat(fake.balanceCalls).hasSize(2)

        syncService.sync(connectionOf(household), SyncMode.MANUAL)
        syncService.sync(connectionOf(household), SyncMode.SCHEDULED)

        assertThat(fake.balanceCalls).hasSize(2)
        assertThat(bankAnchors(household.id)).hasSize(1)
    }

    @Test
    fun `other cash adds to the bank anchor and flows after it move the estimate`() {
        val (user, household) = seed()
        link(household, user)

        bankCash.setOtherCash(household.id, BigDecimal("150.00"), user)
        expense(household, user, today, "40.00")

        assertThat(bankAnchors(household.id).single().amount).isEqualByComparingTo("2150.00")
        assertThat(estimates.estimateAt(household.id, today).estimate).isEqualByComparingTo("2110.00")
    }

    @Test
    fun `an account no longer counted as cash is taken out of the latest anchor in place`() {
        val (user, household) = seed()
        link(household, user)
        val savings = accounts.findAllByConnectionId(connectionOf(household)).single { it.accountUid == "acc-savings" }

        bankCash.setCountsAsCash(household.id, savings.id, false)

        val anchor = bankAnchors(household.id).single()
        assertThat(anchor.adjustmentDate).isEqualTo(yesterday)
        assertThat(anchor.amount).isEqualByComparingTo("1200.00")
    }

    @Test
    fun `a balance the bank refuses leaves the sync healthy and counts the other accounts`() {
        val (user, household) = seed()
        fake.failingBalanceUids += "acc-savings"

        link(household, user)

        assertThat(connections.findById(connectionOf(household)).get().status).isEqualTo(ConnectionStatus.active)
        assertThat(bankAnchors(household.id).single().amount).isEqualByComparingTo("1200.00")
    }

    @Test
    fun `without linked balances no bank anchor is written and cash keeps its manual series`() {
        val (user, household) = seed()
        fake.balancesByAccount.clear()

        link(household, user)
        bankCash.setOtherCash(household.id, BigDecimal("300.00"), user)

        assertThat(bankAnchors(household.id)).isEmpty()
        assertThat(estimates.estimateAt(household.id, today).estimate).isNull()
    }

    private fun bankAnchors(householdId: UUID): List<CashAdjustment> =
        adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(householdId)
            .filter { it.source == CashAdjustmentSource.bank }

    private fun connectionOf(household: Household): UUID =
        connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id

    private fun expense(household: Household, user: User, date: LocalDate, amount: String) {
        transactions.save(
            Transaction(
                householdId = household.id,
                occurrenceDate = date,
                direction = Direction.expense,
                categoryCode = "misc",
                amount = BigDecimal(amount),
                description = "Groceries",
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
    }

    private fun link(household: Household, user: User) {
        bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL", label = "Test"), user, HouseholdRole.owner)
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "bankcash${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        credentialsService.save(household.id, "test-app", BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        return user to household
    }
}
