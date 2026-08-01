package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.connector.AuthorizedAccount
import com.sephilabs.sharedledger.bank.connector.BankMovement
import com.sephilabs.sharedledger.bank.connector.FetchStrategy
import com.sephilabs.sharedledger.bank.connector.MovementPage
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.bank.sync.BankSyncService
import com.sephilabs.sharedledger.bank.sync.SyncMode
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.portfolio.price.FxRate
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.math.BigDecimal
import java.time.Instant
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
    private val syncRuns: BankSyncRunRepository,
    private val transactions: TransactionRepository,
    private val movementRepo: MovementRepository,
    private val fxRates: FxRateRepository,
    private val categories: CategoryService,
    private val props: AppProperties,
    private val credentialsService: BankCredentialsService,
    private val fake: FakeBankConnector,
) : IntegrationTestBase() {

    /** Linking resolves its redirect URL from the current request (see BankCallbackUrl), so these
     *  service-level tests bind one. */
    @BeforeEach
    fun bindRequest() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
    }

    @AfterEach
    fun unbindRequest() {
        RequestContextHolder.resetRequestAttributes()
    }

    // The fake connector is a shared singleton across tests; reset its seeded state each time.
    @BeforeEach
    fun resetConnector() {
        fake.movements.clear()
        fake.lastState = null
        fake.fetchCalls.clear()
        fake.scriptedPages.clear()
        fake.failWithRateLimitAfter = -1
        fake.failWithProviderErrorAfter = -1
        fake.failingAccountUids.clear()
        fake.failLongestOnly = false
        fake.accounts = listOf(AuthorizedAccount("acc-1", "NL00INGB0001234567", "Checking", "EUR"))
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
        val expenseItem = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.first { it.direction == Direction.expense }
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
        val remaining = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        pendingService.reject(household.id, remaining.id, user)
        assertThat(pendingService.pendingCount(household.id)).isZero()
        assertThat(pending.search(household.id, MovementStatus.rejected, null, null, null, PageRequest.of(0, 100)).content).hasSize(1)
        // Still only the one confirmed transaction.
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
    }

    @Test
    fun `confirming a pending item as a net-worth movement links a movement, not a transaction`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("mv-1", today.minusDays(1), Direction.expense, "500.00", "Broker Transfer"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        val confirmed = pendingService.confirmAsMovement(
            household.id,
            item.id,
            ConfirmAsMovementRequest(type = MovementType.contribution, assetClassCode = "etfs"),
            user,
        )

        // The item is terminal, linked to a movement (not a transaction), and gone from the inbox.
        assertThat(confirmed.status).isEqualTo(MovementStatus.confirmed)
        assertThat(confirmed.createdMovementId).isNotNull()
        assertThat(confirmed.createdTransactionId).isNull()
        assertThat(pendingService.pendingCount(household.id)).isZero()

        // A net-worth movement was created with the item's date + amount; the ledger is untouched.
        val movements = movementRepo.findInRange(household.id, today.minusDays(30), today)
        assertThat(movements).hasSize(1)
        val m = movements.single()
        assertThat(m.type).isEqualTo(MovementType.contribution)
        assertThat(m.assetClassCode).isEqualTo("etfs")
        assertThat(m.amount).isEqualByComparingTo("500.00")
        assertThat(m.movementDate).isEqualTo(today.minusDays(1))
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
    }

    @Test
    fun `confirming as a movement with an invalid target is rejected and creates nothing`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("mv-2", today.minusDays(1), Direction.expense, "10.00", "Shop"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()

        // contribution requires an asset class, not a liability → MOVEMENT_TARGET_INVALID.
        assertThatThrownBy {
            pendingService.confirmAsMovement(household.id, item.id, ConfirmAsMovementRequest(type = MovementType.contribution), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("MOVEMENT_TARGET_INVALID")

        // The item stays pending and neither a movement nor a transaction was created.
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        assertThat(movementRepo.findInRange(household.id, today.minusDays(30), today)).isEmpty()
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
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

        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
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
        assertThat(pending.search(household.id, MovementStatus.rejected, null, null, null, PageRequest.of(0, 100)).content).isEmpty()
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
    }

    @Test
    fun `restoring a non-rejected item is a conflict`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("nr-1", today.minusDays(1), Direction.expense, "5.00", "Shop"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
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

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        assertThat(item.suggestedCategoryCode).isEqualTo(expenseCat)

        val result = pendingService.confirmBatch(household.id, listOf(ConfirmBatchItem(item.id)), user)
        assertThat(result.confirmed).isEqualTo(1)
        assertThat(result.skipped).isEmpty()
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
        // The manual rule already covered the movement, so confirming learns nothing new.
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)).hasSize(1)
    }

    @Test
    fun `confirming a movement covered by a rule creates no learned rule, even with a different category`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val otherExpenseCat = categories.listForHousehold(household.id)
            .first { it.kind == "expense" && it.code != expenseCat }.code
        categorization.create(
            household.id,
            CategorizationRuleRequest(
                matchField = RuleField.counterparty,
                matchOp = RuleOp.contains,
                matchValue = "Albert",
                categoryCode = expenseCat,
                direction = Direction.expense,
            ),
            user,
        )
        fake.movements.add(movement("cv-1", today.minusDays(1), Direction.expense, "25.00", "Albert Heijn"))
        link(household, user)

        // Confirm with a DIFFERENT category than the rule suggests — a one-off exception, not a new norm.
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        pendingService.confirm(household.id, item.id, ConfirmMovementRequest(categoryCode = otherExpenseCat), user)

        // The transaction got the confirmed category, but no learned rule piled on the matching one.
        val txns = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
        assertThat(txns.single().categoryCode).isEqualTo(otherExpenseCat)
        val allRules = rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)
        assertThat(allRules).hasSize(1)
        assertThat(allRules.single().source).isEqualTo(RuleSource.manual)
    }

    @Test
    fun `confirming an uncovered movement learns a rule for its counterparty`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.add(movement("lr-1", today.minusDays(1), Direction.expense, "9.50", "Coffee Kiosk"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        pendingService.confirm(household.id, item.id, ConfirmMovementRequest(categoryCode = expenseCat), user)

        val learned = rules.findAllByHouseholdIdOrderByPriorityAsc(household.id).single()
        assertThat(learned.source).isEqualTo(RuleSource.learned)
        assertThat(learned.matchField).isEqualTo(RuleField.counterparty)
        assertThat(learned.matchOp).isEqualTo(RuleOp.equals)
        assertThat(learned.matchValue).isEqualTo("Coffee Kiosk")
        assertThat(learned.categoryCode).isEqualTo(expenseCat)

        // The next movement from the same payee arrives pre-categorised, and confirming it
        // creates no second rule — the learned one now covers the case.
        fake.movements.clear()
        fake.movements.add(movement("lr-2", today, Direction.expense, "4.00", "Coffee Kiosk"))
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        syncService.sync(connectionId)
        val next = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        assertThat(next.suggestedCategoryCode).isEqualTo(expenseCat)
        pendingService.confirm(household.id, next.id, ConfirmMovementRequest(), user)
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)).hasSize(1)
    }

    @Test
    fun `delete-batch removes only the requested rules of the household`() {
        val (user, household) = seed()
        val expenseCat = expenseCategory(household)
        val created = (1..3).map { i ->
            categorization.create(
                household.id,
                CategorizationRuleRequest(
                    matchField = RuleField.counterparty,
                    matchOp = RuleOp.contains,
                    matchValue = "Shop $i",
                    categoryCode = expenseCat,
                    direction = Direction.expense,
                ),
                user,
            )
        }
        // Another household's rule must be untouched even if its id is passed.
        val (otherUser, otherHousehold) = seed()
        val foreign = categorization.create(
            otherHousehold.id,
            CategorizationRuleRequest(
                matchField = RuleField.counterparty,
                matchOp = RuleOp.contains,
                matchValue = "Foreign",
                categoryCode = expenseCategory(otherHousehold),
                direction = Direction.expense,
            ),
            otherUser,
        )

        val result = categorization.deleteBatch(household.id, listOf(created[0].id, created[2].id, foreign.id))

        assertThat(result.deleted).isEqualTo(2)
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id).map { it.id }).containsExactly(created[1].id)
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(otherHousehold.id)).hasSize(1)
    }

    @Test
    fun `batch confirm skips items without a category and confirms with a per-item category`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.add(movement("b-1", today.minusDays(1), Direction.expense, "5.00", "Kiosk"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
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

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        assertThat(item.amount).isEqualByComparingTo("90.00")
        assertThat(item.originalAmount).isEqualByComparingTo("100.00")
        assertThat(item.originalCurrency).isEqualTo("USD")
    }

    @Test
    fun `visibility reflects configuration and linked connections`() {
        val (user, household) = seed()
        val before = bankService.config(household.id)
        assertThat(before.credentialsConfigured).isTrue()
        assertThat(before.connectionCount).isZero()

        link(household, user)

        assertThat(bankService.config(household.id).connectionCount).isEqualTo(1)
    }

    @Test
    fun `apply-rules categorises only uncategorized pending items and leaves them pending`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val otherExpenseCat = categories.listForHousehold(household.id)
            .first { it.kind == "expense" && it.code != expenseCat }.code

        fake.movements.addAll(
            listOf(
                movement("ar-1", today.minusDays(1), Direction.expense, "9.99", "Corner Store"),
                movement("ar-2", today.minusDays(1), Direction.expense, "4.00", "Coffee Kiosk"),
                movement("ar-3", today.minusDays(1), Direction.expense, "7.00", "Unmatched Shop"),
            ),
        )
        link(household, user)

        // Nothing was categorised at sync time (no rules existed yet).
        val ingested = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        assertThat(ingested).allMatch { it.suggestedCategoryCode == null }
        val corner = ingested.first { it.counterparty == "Corner Store" }
        val kiosk = ingested.first { it.counterparty == "Coffee Kiosk" }
        val shop = ingested.first { it.counterparty == "Unmatched Shop" }

        // Corner Store is already categorised by hand; a rule now matches the Kiosk only.
        pendingService.edit(household.id, corner.id, EditMovementRequest(suggestedCategoryCode = otherExpenseCat))
        categorization.create(
            household.id,
            CategorizationRuleRequest(
                matchField = RuleField.counterparty,
                matchOp = RuleOp.contains,
                matchValue = "Kiosk",
                categoryCode = expenseCat,
                direction = Direction.expense,
            ),
            user,
        )

        val result = pendingService.applyRulesToPending(household.id)

        // Only the uncategorized-and-matching item was categorised.
        assertThat(result.categorized).isEqualTo(1)
        assertThat(pending.findByIdAndHouseholdId(kiosk.id, household.id)!!.suggestedCategoryCode).isEqualTo(expenseCat)
        // The pre-categorised item is left untouched (never overwritten).
        assertThat(pending.findByIdAndHouseholdId(corner.id, household.id)!!.suggestedCategoryCode).isEqualTo(otherExpenseCat)
        // A movement matching no rule stays uncategorized.
        assertThat(pending.findByIdAndHouseholdId(shop.id, household.id)!!.suggestedCategoryCode).isNull()

        // No confirm step: every item is still pending and no transaction was created.
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(3)
        listOf(corner, kiosk, shop).forEach {
            assertThat(pending.findByIdAndHouseholdId(it.id, household.id)!!.status).isEqualTo(MovementStatus.pending)
        }
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
    }

    @Test
    fun `pending filters (search, categorisation, duplicates) are applied across the full dataset`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.addAll(
            listOf(
                movement("f-1", today.minusDays(1), Direction.expense, "12.50", "Grocery Store"),
                movement("f-2", today.minusDays(2), Direction.expense, "8.00", "Coffee House"),
                movement("f-3", today.minusDays(3), Direction.income, "100.00", "Employer Payroll"),
            ),
        )
        link(household, user)

        // Categorise one item; the server-side "categorised" filter keys off suggestedCategoryCode.
        val ingested = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        val grocery = ingested.first { it.counterparty == "Grocery Store" }
        pendingService.edit(household.id, grocery.id, EditMovementRequest(suggestedCategoryCode = expenseCat))

        // Text search matches counterparty/description/reference, over every row (not just a page).
        val searchHit = pendingService.list(household.id, MovementStatus.pending, null, "coffee", null, false, 0, 50)
        assertThat(searchHit.items.map { it.counterparty }).containsExactly("Coffee House")

        // Categorisation filters split the whole dataset by presence of a suggested category.
        val categorised = pendingService.list(household.id, MovementStatus.pending, null, null, PendingCategorisation.categorized, false, 0, 50)
        assertThat(categorised.items.map { it.counterparty }).containsExactly("Grocery Store")
        val uncategorised = pendingService.list(household.id, MovementStatus.pending, null, null, PendingCategorisation.uncategorized, false, 0, 50)
        assertThat(uncategorised.items.map { it.counterparty }).containsExactlyInAnyOrder("Coffee House", "Employer Payroll")

        // Pagination runs on the filtered dataset: page size 1 still reports the full filtered total.
        val firstPage = pendingService.list(household.id, MovementStatus.pending, null, null, PendingCategorisation.uncategorized, false, 0, 1)
        assertThat(firstPage.items).hasSize(1)
        assertThat(firstPage.total).isEqualTo(2)

        // Duplicates-only: confirm the grocery item so a matching transaction exists, ingest a second
        // identical movement, and only that possible-duplicate is returned by the filter.
        pendingService.confirm(household.id, grocery.id, ConfirmMovementRequest(categoryCode = expenseCat), user)
        fake.movements.clear()
        fake.movements.add(movement("f-4", today.minusDays(1), Direction.expense, "12.50", "Grocery Store 2"))
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        syncService.sync(connectionId)

        val dupes = pendingService.list(household.id, MovementStatus.pending, null, null, null, true, 0, 50)
        assertThat(dupes.items.map { it.counterparty }).containsExactly("Grocery Store 2")
        assertThat(dupes.total).isEqualTo(1)
        assertThat(dupes.items.single().possibleDuplicate).isTrue()
    }

    // --- helpers ---------------------------------------------------------------------------------

    @Test
    fun `pagination continues while a continuation_key is present, including an empty page`() {
        val (user, household) = seed()
        link(household, user) // initial sync ingests nothing (no movements / scripted pages yet)
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id

        // A bank that returns ~20/page: 20, 20, an EMPTY page that STILL carries a key, then 20.
        fake.fetchCalls.clear()
        fake.scriptedPages.addAll(
            listOf(
                scriptedPage(0, 20, "k1"),
                scriptedPage(20, 20, "k2"),
                MovementPage(emptyList(), "k3"), // empty but more to come — must not stop here
                scriptedPage(40, 20, null),
            ),
        )

        val ingested = syncService.sync(connectionId, SyncMode.INITIAL, null)

        // Did not stop at ~60 or at the empty page — paged through all four responses.
        assertThat(ingested).isEqualTo(60)
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(60)
        assertThat(fake.fetchCalls).hasSize(4)
        assertThat(fake.scriptedPages).isEmpty()
    }

    @Test
    fun `initial sync runs outside the per-day call budget`() {
        val (user, household) = seed()
        link(household, user)
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id

        // Pre-exhaust the background budget for today.
        val maxCalls = props.enableBanking.maxCallsPerDay
        connections.findById(connectionId).get().let {
            it.callsUsedToday = maxCalls
            it.callsResetOn = LocalDate.now()
            connections.save(it)
        }

        // More pages than the cap; an interactive INITIAL sync must still page through them all.
        val pageCount = maxCalls + 3
        fake.fetchCalls.clear()
        repeat(pageCount) { i ->
            fake.scriptedPages.add(scriptedPage(i * 5, 5, if (i < pageCount - 1) "k$i" else null))
        }

        val ingested = syncService.sync(connectionId, SyncMode.INITIAL, null)

        assertThat(ingested).isEqualTo(pageCount * 5)
        assertThat(fake.fetchCalls).hasSize(pageCount) // not capped at maxCalls
        // Interactive syncs don't consume the background allowance — it's still exactly what we set.
        assertThat(connections.findById(connectionId).get().callsUsedToday).isEqualTo(maxCalls)
    }

    @Test
    fun `initial on-link sync uses the longest strategy for full history`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.addAll(
            listOf(
                movement("h-1", today.minusDays(400), Direction.expense, "5.00", "Old"),
                movement("h-2", today.minusDays(1), Direction.income, "9.00", "New"),
            ),
        )
        link(household, user)

        // Full history: the 400-day-old movement (beyond any incremental window) is ingested.
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)
        assertThat(fake.fetchCalls).isNotEmpty
        assertThat(fake.fetchCalls).allMatch { it.strategy == FetchStrategy.LONGEST }
    }

    @Test
    fun `background sync uses default strategy from the last sync point minus overlap`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val lastBooking = today.minusDays(2)
        fake.movements.add(movement("bg-1", lastBooking, Direction.expense, "3.00", "Shop"))
        link(household, user) // initial (longest) ingests bg-1 and sets the sync point
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id

        fake.fetchCalls.clear()
        syncService.sync(connectionId, SyncMode.SCHEDULED, null)

        val call = fake.fetchCalls.single()
        assertThat(call.strategy).isEqualTo(FetchStrategy.DEFAULT)
        assertThat(call.dateFrom).isEqualTo(lastBooking.minusDays(props.enableBanking.syncOverlapDays))
        // Inclusive upper bound → today, never a future date (strict ASPSPs reject one).
        assertThat(call.dateTo).isEqualTo(today)
        assertThat(call.interactive).isFalse()
    }

    @Test
    fun `the overlap re-fetch creates no duplicates`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("ov-1", today.minusDays(1), Direction.expense, "4.00", "Shop"))
        link(household, user)
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)

        // The incremental window re-reads the overlap (which includes ov-1) but must not duplicate it.
        val newlyIngested = syncService.sync(connectionId, SyncMode.SCHEDULED, null)
        assertThat(newlyIngested).isZero()
        assertThat(pending.findAll().count { it.householdId == household.id }).isEqualTo(1)
    }

    @Test
    fun `a background rate limit backs off, surfaces status, and the next run is skipped`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("rl-1", today.minusDays(1), Direction.expense, "2.00", "Shop"))
        link(household, user)
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id

        // The first background fetch is rate-limited by the ASPSP.
        fake.failWithRateLimitAfter = 0
        fake.fetchCalls.clear()
        syncService.sync(connectionId, SyncMode.SCHEDULED, null)

        val connection = connections.findById(connectionId).get()
        assertThat(connection.syncBackoffUntil).isNotNull()
        assertThat(connection.syncBackoffUntil!!.isAfter(Instant.now())).isTrue()
        // Transient limit, not a hard failure — the connection stays active.
        assertThat(connection.status).isEqualTo(ConnectionStatus.active)

        val run = syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(connectionId)!!
        assertThat(run.status).isEqualTo(SyncRunStatus.error)
        assertThat(run.errorCode).isEqualTo("ASPSP_RATE_LIMIT_EXCEEDED")

        // Next scheduled run while still backing off is skipped — no provider call is attempted.
        fake.failWithRateLimitAfter = -1
        fake.fetchCalls.clear()
        val skipped = syncService.sync(connectionId, SyncMode.SCHEDULED, null)
        assertThat(skipped).isZero()
        assertThat(fake.fetchCalls).isEmpty()
    }

    @Test
    fun `a provider error mid-pagination keeps the pages already fetched and records the code`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        link(household, user)
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        val syncPointBefore = connections.findById(connectionId).get().lastSyncedAt

        // Page 1 arrives, then the ASPSP breaks (Bankinter's 400 ASPSP_ERROR) — the movements we
        // already hold must survive instead of being thrown away with the run.
        fake.scriptedPages.addLast(
            MovementPage(
                movements = listOf(movement("pp-1", today.minusDays(2), Direction.expense, "7.00", "Shop")),
                continuationKey = "next-page",
            ),
        )
        fake.failWithProviderErrorAfter = 1
        fake.fetchCalls.clear()

        val ingested = syncService.sync(connectionId, SyncMode.SCHEDULED, null)

        assertThat(ingested).isEqualTo(1)
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        val run = syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(connectionId)!!
        assertThat(run.status).isEqualTo(SyncRunStatus.error)
        // The provider's own code, not a generic BANK_PROVIDER_ERROR or a bare HTTP status.
        assertThat(run.errorCode).isEqualTo("ASPSP_ERROR")
        assertThat(run.newMovements).isEqualTo(1)
        val connection = connections.findById(connectionId).get()
        assertThat(connection.status).isEqualTo(ConnectionStatus.suspended)
        // The failed run must not advance the sync point — a full-history retry stays possible.
        assertThat(connection.lastSyncedAt).isEqualTo(syncPointBefore)
    }

    @Test
    fun `one account the bank refuses does not cost the other accounts their movements`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.accounts = listOf(
            AuthorizedAccount("acc-ok", "NL00INGB0001234567", "Checking", "EUR"),
            AuthorizedAccount("acc-bad", "ES0000000000000000", "Card", "EUR"),
        )
        fake.movements.add(movement("iso-1", today.minusDays(1), Direction.expense, "6.00", "Shop"))
        // The ASPSP refuses one account outright (a stale uid, or a product it won't serve).
        fake.failingAccountUids += "acc-bad"

        link(household, user)

        // The healthy account still delivered, and the run says which provider code broke the other.
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        assertThat(fake.fetchCalls.map { it.accountUid }).contains("acc-ok", "acc-bad")
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        val run = syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(connectionId)!!
        assertThat(run.status).isEqualTo(SyncRunStatus.error)
        assertThat(run.errorCode).isEqualTo("ASPSP_ERROR")
        assertThat(run.newMovements).isEqualTo(1)
    }

    @Test
    fun `an ASPSP that refuses the dateless full-history fetch is retried with a bounded window`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.add(movement("fb-1", today.minusDays(1), Direction.expense, "8.00", "Shop"))
        // Bankinter's shape: strategy=longest is refused, a bounded date range is served.
        fake.failingAccountUids += "acc-1"
        fake.failLongestOnly = true

        link(household, user)

        assertThat(fake.fetchCalls.first().strategy).isEqualTo(FetchStrategy.LONGEST)
        val retry = fake.fetchCalls[1]
        assertThat(retry.strategy).isEqualTo(FetchStrategy.DEFAULT)
        assertThat(retry.dateFrom).isEqualTo(today.minusDays(props.enableBanking.backfillDays))
        assertThat(retry.dateTo).isEqualTo(today)
        // Recovered: the movement is in the inbox and the run counts as a success.
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id
        assertThat(syncRuns.findFirstByConnectionIdOrderByStartedAtDesc(connectionId)!!.status)
            .isEqualTo(SyncRunStatus.success)
    }

    @Test
    fun `a stale background window is clamped to the unattended history limit`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        // A long-dormant account: its newest stored movement is far outside the background window.
        fake.movements.add(movement("old-1", today.minusDays(300), Direction.expense, "5.00", "Shop"))
        link(household, user) // initial sync is strategy=longest, so it ingests the old movement
        val connectionId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().id

        fake.fetchCalls.clear()
        syncService.sync(connectionId, SyncMode.SCHEDULED, null)

        // Without the clamp this would ask for 303 days of history and be rejected outright.
        val call = fake.fetchCalls.single()
        assertThat(call.dateFrom).isEqualTo(today.minusDays(props.enableBanking.backfillDays))
        assertThat(call.dateTo).isEqualTo(today)
    }

    @Test
    fun `pending count breaks down per connection, labelled and largest first`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.addAll(
            listOf(
                movement("m-1", today.minusDays(2), Direction.expense, "10.00", "Shop A"),
                movement("m-2", today.minusDays(1), Direction.expense, "20.00", "Shop B"),
            ),
        )
        // First connection ingests the two movements above.
        bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL", label = "Joint account"), user, HouseholdRole.owner)
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)
        // The second connection (unlabelled → bank-name fallback) also sees a third movement: 3 vs 2.
        fake.movements.add(movement("m-3", today, Direction.expense, "30.00", "Shop C"))
        bankService.startLink(household.id, StartLinkRequest(aspspName = "Revolut", country = "NL", label = null), user, HouseholdRole.owner)
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)

        val counts = pendingService.pendingCounts(household.id)
        assertThat(counts.count).isEqualTo(5)
        assertThat(counts.byConnection.map { it.label to it.count })
            .containsExactly("Revolut" to 3L, "Joint account" to 2L)

        // Confirming one Revolut item moves its line, not just the total (ties order alphabetically).
        val revolutId = counts.byConnection.first().connectionId
        val item = pending.search(household.id, MovementStatus.pending, revolutId, null, null, PageRequest.of(0, 100)).content.first()
        pendingService.confirm(household.id, item.id, ConfirmMovementRequest(categoryCode = expenseCategory(household)), user)

        val after = pendingService.pendingCounts(household.id)
        assertThat(after.count).isEqualTo(4)
        assertThat(after.byConnection.map { it.label to it.count })
            .containsExactly("Joint account" to 2L, "Revolut" to 2L)
    }

    private fun scriptedPage(startIdx: Int, count: Int, key: String?): MovementPage {
        val today = LocalDate.now()
        val ms = (0 until count).map { i ->
            val idx = startIdx + i
            movement("pg-$idx", today.minusDays((idx % 300 + 1).toLong()), Direction.expense, "1.00", "Shop $idx")
        }
        return MovementPage(ms, key)
    }

    @Test
    fun `a member links their own bank and manages only that connection`() {
        val (owner, household) = seed()
        val relative = users.save(User(email = "rel${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val roommate = users.save(User(email = "mate${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))

        // A plain member can link — no owner role involved anywhere in the flow.
        bankService.startLink(
            household.id,
            StartLinkRequest(aspspName = "ING", country = "NL", label = "Relative's ING"),
            relative,
            HouseholdRole.member,
        )
        val linked = bankService.completeLink(
            household.id,
            CompleteLinkRequest(code = "code", state = fake.lastState!!),
            relative,
            HouseholdRole.member,
        )
        assertThat(linked.canManage).isTrue()

        // The linker and any owner may manage it; another member sees it but may not.
        assertThat(bankService.listConnections(household.id, relative, HouseholdRole.member).single().canManage).isTrue()
        assertThat(bankService.listConnections(household.id, owner, HouseholdRole.owner).single().canManage).isTrue()
        assertThat(bankService.listConnections(household.id, roommate, HouseholdRole.member).single().canManage).isFalse()

        assertThatThrownBy { bankService.delete(household.id, linked.id, roommate, HouseholdRole.member) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("NOT_CONNECTION_MANAGER")
        assertThatThrownBy {
            bankService.update(household.id, linked.id, UpdateConnectionRequest(label = "hijacked"), roommate, HouseholdRole.member)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("NOT_CONNECTION_MANAGER")
        assertThatThrownBy {
            bankService.startLink(
                household.id,
                StartLinkRequest(aspspName = "ING", country = "NL", relinkConnectionId = linked.id),
                roommate,
                HouseholdRole.member,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("NOT_CONNECTION_MANAGER")

        // The linker renames their own connection; the owner can too.
        assertThat(bankService.update(household.id, linked.id, UpdateConnectionRequest(label = "Mine"), relative, HouseholdRole.member).label)
            .isEqualTo("Mine")
        assertThat(bankService.update(household.id, linked.id, UpdateConnectionRequest(label = "Ours"), owner, HouseholdRole.owner).label)
            .isEqualTo("Ours")
    }

    @Test
    fun `a member cannot complete a link another member started`() {
        val (_, household) = seed()
        val relative = users.save(User(email = "rel${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val roommate = users.save(User(email = "mate${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))

        bankService.startLink(
            household.id,
            StartLinkRequest(aspspName = "ING", country = "NL", label = null),
            relative,
            HouseholdRole.member,
        )
        assertThatThrownBy {
            bankService.completeLink(
                household.id,
                CompleteLinkRequest(code = "code", state = fake.lastState!!),
                roommate,
                HouseholdRole.member,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_AUTH_STATE_MISMATCH")

        assertThat(connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id)).isEmpty()
    }

    private fun link(household: Household, user: User) {
        bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL", label = "Test"), user, HouseholdRole.owner)
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)
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

    /** Credentials are per household now, so seeding them is part of "this household is set up". */
    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "bank${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        credentialsService.save(household.id, TEST_APP_ID, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        return user to household
    }

    private companion object {
        const val TEST_APP_ID = "test-app"
    }
}
