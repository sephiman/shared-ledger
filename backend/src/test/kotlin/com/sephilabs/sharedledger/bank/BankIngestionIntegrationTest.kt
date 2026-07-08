package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.connector.BankMovement
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.bank.sync.BankSyncService
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.price.FxRate
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.LocalDate

@Import(FakeBankConnectorConfig::class)
class BankIngestionIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val bankService: BankService,
    private val pendingService: PendingMovementService,
    private val syncService: BankSyncService,
    private val rules: CategorizationRuleRepository,
    private val categorization: CategorizationService,
    private val pending: PendingMovementRepository,
    private val connections: BankConnectionRepository,
    private val transactions: TransactionRepository,
    private val fxRates: FxRateRepository,
    private val categories: CategoryService,
    private val fake: FakeBankConnector,
) : IntegrationTestBase() {

    // The fake connector is a shared singleton across tests; reset its seeded movements each time.
    @BeforeEach
    fun resetConnector() {
        fake.movements.clear()
        fake.lastState = null
    }

    @Test
    fun `link runs an async backfill, confirm generates a transaction, and re-sync is idempotent`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.addAll(
            listOf(
                movement("m-1", today.minusDays(2), Direction.expense, "12.50", "Grocery Store"),
                movement("m-2", today.minusDays(1), Direction.income, "100.00", "Employer"),
            ),
        )

        link(household, user)

        // Initial sync ran off-thread (synchronous test executor) and populated the inbox.
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)

        val expenseCat = expenseCategory(household)
        val expenseItem = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content.first { it.direction == Direction.expense }
        val confirmed = pendingService.confirm(household.id, expenseItem.id, ConfirmMovementRequest(categoryCode = expenseCat), user)

        assertThat(confirmed.status).isEqualTo(MovementStatus.confirmed)
        assertThat(confirmed.createdTransactionId).isNotNull()
        val txns = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
        assertThat(txns).hasSize(1)
        assertThat(txns.first().amount).isEqualByComparingTo("12.50")
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)

        // Re-sync ingests nothing new (idempotent by connection + bank movement id).
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        val newlyIngested = syncService.sync(connectionId)
        assertThat(newlyIngested).isZero()
        assertThat(pending.findAll().count { it.householdId == household.id }).isEqualTo(2)

        // Reject the remaining pending item — creates nothing, disappears from the pending inbox.
        val remaining = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content.single()
        pendingService.reject(household.id, remaining.id, user)
        assertThat(pendingService.pendingCount(household.id)).isZero()
        assertThat(pending.search(household.id, MovementStatus.rejected, null, PageRequest.of(0, 100)).content).hasSize(1)
        // Still only the one confirmed transaction.
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
    }

    @Test
    fun `a rejected item can be restored to pending, individually and by batch`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.addAll(
            listOf(
                movement("r-1", today.minusDays(2), Direction.expense, "12.50", "Shop A"),
                movement("r-2", today.minusDays(1), Direction.expense, "8.00", "Shop B"),
            ),
        )
        link(household, user)
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)

        val items = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content
        val (first, second) = items[0] to items[1]

        // Reject both, then restore one individually and one via batch.
        pendingService.reject(household.id, first.id, user)
        pendingService.reject(household.id, second.id, user)
        assertThat(pendingService.pendingCount(household.id)).isZero()

        val restored = pendingService.restore(household.id, first.id)
        assertThat(restored.status).isEqualTo(MovementStatus.pending)
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)

        val batch = pendingService.restoreBatch(household.id, listOf(second.id))
        assertThat(batch.restored).isEqualTo(1)
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)

        // Processing audit fields are cleared and nothing is left rejected; no transactions created.
        val back = pending.findByIdAndHouseholdId(first.id, household.id)!!
        assertThat(back.processedAt).isNull()
        assertThat(back.processedByUserId).isNull()
        assertThat(pending.search(household.id, MovementStatus.rejected, null, PageRequest.of(0, 100)).content).isEmpty()
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
    }

    @Test
    fun `restoring a non-rejected item is a conflict`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("nr-1", today.minusDays(1), Direction.expense, "5.00", "Shop"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content.single()
        assertThatThrownBy { pendingService.restore(household.id, item.id) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("PENDING_MOVEMENT_NOT_REJECTED")
    }

    @Test
    fun `a rule pre-categorises movements and batch confirm generates transactions`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        categorization.create(
            household.id,
            CategorizationRuleRequest(
                matchField = RuleField.counterparty,
                matchOp = RuleOp.contains,
                matchValue = "Store",
                categoryCode = expenseCat,
                direction = Direction.expense,
            ),
            user,
        )
        fake.movements.add(movement("m-10", today.minusDays(1), Direction.expense, "9.99", "Corner Store"))

        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content.single()
        assertThat(item.suggestedCategoryCode).isEqualTo(expenseCat)

        val result = pendingService.confirmBatch(household.id, listOf(ConfirmBatchItem(item.id)), user)
        assertThat(result.confirmed).isEqualTo(1)
        assertThat(result.skipped).isEmpty()
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
        // A learned rule already existed for this counterparty; no duplicate rule is created.
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)).hasSize(2)
    }

    @Test
    fun `batch confirm skips items without a category and confirms with a per-item category`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.add(movement("b-1", today.minusDays(1), Direction.expense, "5.00", "Kiosk"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content.single()
        assertThat(item.suggestedCategoryCode).isNull()

        // No rule + no chosen category → skipped, nothing created.
        val skippedResult = pendingService.confirmBatch(household.id, listOf(ConfirmBatchItem(item.id)), user)
        assertThat(skippedResult.confirmed).isZero()
        assertThat(skippedResult.skipped).containsExactly(item.id)

        // Chosen per-item category → confirmed, transaction generated.
        val ok = pendingService.confirmBatch(household.id, listOf(ConfirmBatchItem(item.id, categoryCode = expenseCat)), user)
        assertThat(ok.confirmed).isEqualTo(1)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
    }

    @Test
    fun `a non-EUR movement is converted to EUR at the booking-date rate, keeping the original`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val booking = today.minusDays(1)
        fxRates.save(
            FxRate(provider = "frankfurter", baseCurrency = "USD", quoteCurrency = "EUR", rate = BigDecimal("0.90"), rateDate = booking),
        )
        fake.movements.add(
            BankMovement(
                bankMovementId = "usd-1",
                bookingDate = booking,
                valueDate = booking,
                direction = Direction.expense,
                amount = BigDecimal("100.00"),
                currency = "USD",
                counterparty = "US Shop",
                description = "Gadget",
                reference = null,
            ),
        )

        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, PageRequest.of(0, 100)).content.single()
        assertThat(item.amount).isEqualByComparingTo("90.00")
        assertThat(item.originalAmount).isEqualByComparingTo("100.00")
        assertThat(item.originalCurrency).isEqualTo("USD")
    }

    @Test
    fun `visibility reflects configuration and linked connections`() {
        val (user, household) = seed()
        val before = bankService.config(household.id)
        assertThat(before.featureEnabled).isTrue()
        assertThat(before.connectionCount).isZero()

        link(household, user)

        assertThat(bankService.config(household.id).connectionCount).isEqualTo(1)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun link(household: Household, user: User) {
        bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL", label = "Test"), user)
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user)
    }

    private fun expenseCategory(household: Household): String =
        categories.listForHousehold(household.id).first { it.kind == "expense" }.code

    private fun movement(id: String, date: LocalDate, direction: Direction, amount: String, counterparty: String) =
        BankMovement(
            bankMovementId = id,
            bookingDate = date,
            valueDate = date,
            direction = direction,
            amount = BigDecimal(amount),
            currency = "EUR",
            counterparty = counterparty,
            description = "Test $counterparty",
            reference = null,
        )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "bank${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
