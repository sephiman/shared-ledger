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
import com.sephilabs.sharedledger.notification.RecordingTelegramClient
import com.sephilabs.sharedledger.notification.TelegramCrypto
import com.sephilabs.sharedledger.notification.TelegramSettings
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.price.FxRate
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ResourceLock("fake-bank-connector")
// The recording Telegram client is a singleton this class clears per test, which would otherwise wipe
// another class's messages between its act and assert.
@ResourceLock("recording-telegram")
class BankIngestionIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val bankService: BankService,
    private val pendingService: PendingMovementService,
    private val syncService: BankSyncService,
    private val rules: CategorizationRuleRepository,
    private val categorization: CategorizationService,
    private val pending: PendingMovementRepository,
    private val links: PendingMovementTransactionRepository,
    private val connections: BankConnectionRepository,
    private val syncRuns: BankSyncRunRepository,
    private val transactions: TransactionRepository,
    private val templates: RecurringTemplateRepository,
    private val movementRepo: MovementRepository,
    private val fxRates: FxRateRepository,
    private val categories: CategoryService,
    private val props: AppProperties,
    private val credentialsService: BankCredentialsService,
    private val fake: FakeBankConnector,
    // For the one assertion that a split notifies once, not once per created transaction.
    private val telegram: RecordingTelegramClient,
    private val telegramSettings: TelegramSettingsRepository,
    private val telegramCrypto: TelegramCrypto,
) : IntegrationTestBase() {

    @BeforeEach
    fun resetTelegram() {
        telegram.sent.clear()
    }

    /** Linking resolves its redirect URL from the current request (see BankCallbackUrl), so bind one. */
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

    /** The UPDATE notification isn't asserted here: TelegramNotificationIntegrationTest covers
     *  TransactionService.update, and "same id, no new row" below is what proves replace goes through it. */
    @Test
    fun `replacing a possible duplicate updates the existing transaction in place`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        // A recurring template so the assertion below covers "the linkage is preserved, never detached".
        val template = templates.save(
            RecurringTemplate(
                householdId = household.id,
                direction = Direction.expense,
                categoryCode = expenseCat,
                amount = BigDecimal("40.00"),
                cadence = Cadence.monthly,
                dayOfMonth = 1,
                startDate = today.minusMonths(6),
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        // Entered by hand two days before the bank booked it — same amount, so inside the duplicate window.
        val manual = manualTransaction(household, user, today.minusDays(3), expenseCat, "40.00", "Dinner with Ana", template.id)
        fake.movements.add(movement("dup-1", today.minusDays(1), Direction.expense, "40.00", "RESTAURANT"))
        link(household, user)

        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        assertThat(pendingService.list(household.id, MovementStatus.pending, null, null, null, false, 0, 50).items.single().possibleDuplicate).isTrue()

        val candidates = pendingService.duplicateCandidates(household.id, item.id)
        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().transactionId).isEqualTo(manual.id)
        assertThat(candidates.single().bankLinked).isFalse()

        val replaced = pendingService.replace(household.id, item.id, ReplaceTransactionRequest(transactionId = manual.id), user)

        assertThat(replaced.status).isEqualTo(MovementStatus.confirmed)
        assertThat(replaced.createdTransactionId).isEqualTo(manual.id)
        assertThat(pendingService.pendingCount(household.id)).isZero()

        // Still exactly one transaction, and it is the same row — updated, not deleted and recreated.
        val all = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
        assertThat(all).hasSize(1)
        val updated = all.single()
        assertThat(updated.id).isEqualTo(manual.id)
        assertThat(updated.occurrenceDate).isEqualTo(today.minusDays(1))
        assertThat(updated.amount).isEqualByComparingTo("40.00")
        assertThat(updated.description).isEqualTo("RESTAURANT – Test RESTAURANT")
        // The human-chosen category and the template link survive; updatedBy is the confirming user.
        assertThat(updated.categoryCode).isEqualTo(expenseCat)
        assertThat(updated.recurringTemplateId).isEqualTo(template.id)
        assertThat(updated.updatedByUserId).isEqualTo(user.id)
    }

    @Test
    fun `replace refuses a transaction that is not a match and writes nothing`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        // Same window, different amount — never a duplicate candidate, so never a replace target.
        val unrelated = manualTransaction(household, user, today.minusDays(1), expenseCat, "99.00", "Unrelated")
        fake.movements.add(movement("dup-2", today.minusDays(1), Direction.expense, "40.00", "RESTAURANT"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()

        assertThat(pendingService.duplicateCandidates(household.id, item.id)).isEmpty()
        assertThatThrownBy {
            pendingService.replace(household.id, item.id, ReplaceTransactionRequest(transactionId = unrelated.id), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("REPLACE_TARGET_NOT_A_MATCH")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        val untouched = transactions.findById(unrelated.id).orElseThrow()
        assertThat(untouched.occurrenceDate).isEqualTo(today.minusDays(1))
        assertThat(untouched.description).isEqualTo("Unrelated")
    }

    @Test
    fun `a transaction that already resolves a movement cannot be replaced again`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val manual = manualTransaction(household, user, today.minusDays(2), expenseCat, "25.00", "Coffee run")
        // Two bank movements look like the same manual transaction; only the first may claim it.
        fake.movements.addAll(
            listOf(
                movement("dup-3a", today.minusDays(2), Direction.expense, "25.00", "CAFE"),
                movement("dup-3b", today.minusDays(1), Direction.expense, "25.00", "CAFE"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        val (first, second) = items.sortedBy { it.bookingDate }.let { it[0] to it[1] }

        pendingService.replace(household.id, first.id, ReplaceTransactionRequest(transactionId = manual.id), user)

        // The candidate is still surfaced for the second item, but flagged so the dialog can explain it.
        val candidates = pendingService.duplicateCandidates(household.id, second.id)
        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().bankLinked).isTrue()
        assertThatThrownBy {
            pendingService.replace(household.id, second.id, ReplaceTransactionRequest(transactionId = manual.id), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("TRANSACTION_ALREADY_BANK_LINKED")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
    }

    @Test
    fun `replace validates a re-picked category against the direction`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val incomeCat = categories.listForHousehold(household.id).first { it.kind == "income" }.code
        val manual = manualTransaction(household, user, today.minusDays(1), expenseCat, "30.00", "Bakery")
        fake.movements.add(movement("dup-4", today.minusDays(1), Direction.expense, "30.00", "BAKERY"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()

        // An income category on an expense row is refused exactly as it is anywhere else.
        assertThatThrownBy {
            pendingService.replace(household.id, item.id, ReplaceTransactionRequest(transactionId = manual.id, categoryCode = incomeCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("CATEGORY_DIRECTION_MISMATCH")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        assertThat(transactions.findById(manual.id).orElseThrow().categoryCode).isEqualTo(expenseCat)
    }

    @Test
    fun `splitting an item creates one transaction per part, links them all, and learns no rule`() {
        val (user, household) = seed()
        seedTelegram(household.id, user.id)
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val otherExpenseCat = categories.listForHousehold(household.id)
            .first { it.kind == "expense" && it.code != expenseCat }.code
        fake.movements.add(movement("sp-1", today.minusDays(1), Direction.expense, "120.00", "SUPERMARKET"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        // The ingest announces itself ("new movements to review"); what follows is only about the split.
        telegram.sent.clear()

        val split = pendingService.split(
            household.id,
            item.id,
            SplitMovementRequest(
                parts = listOf(
                    SplitPartRequest(amount = BigDecimal("90.00"), categoryCode = expenseCat, description = "Weekly food"),
                    // A blank description falls back to the bank's, like a confirm with no note.
                    SplitPartRequest(amount = BigDecimal("30.00"), categoryCode = otherExpenseCat),
                ),
            ),
            user,
        )

        // The item is terminal and leaves the inbox; no single transaction represents it.
        assertThat(split.status).isEqualTo(MovementStatus.confirmed)
        assertThat(split.createdTransactionId).isNull()
        assertThat(split.createdTransactionIds).hasSize(2)
        assertThat(pendingService.pendingCount(household.id)).isZero()

        // Two transactions, both on the movement's booking date, adding up to its amount exactly.
        val created = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
        assertThat(created).hasSize(2)
        assertThat(created.map { it.id }).containsExactlyInAnyOrderElementsOf(split.createdTransactionIds)
        assertThat(created).allMatch { it.occurrenceDate == today.minusDays(1) }
        assertThat(created).allMatch { it.direction == Direction.expense }
        assertThat(created.sumOf { it.amount }).isEqualByComparingTo("120.00")
        val food = created.first { it.categoryCode == expenseCat }
        val other = created.first { it.categoryCode == otherExpenseCat }
        assertThat(food.amount).isEqualByComparingTo("90.00")
        assertThat(food.description).isEqualTo("Weekly food")
        assertThat(other.amount).isEqualByComparingTo("30.00")
        assertThat(other.description).isEqualTo("SUPERMARKET – Test SUPERMARKET")

        // Every part is linked back, which is what protects them from a later Replace.
        assertThat(links.findAllByPendingMovementId(item.id).map { it.transactionId })
            .containsExactlyInAnyOrderElementsOf(created.map { it.id })

        // One counterparty mapping to two categories is exactly what a learned rule can't express.
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)).isEmpty()

        // One aggregated notification for the whole split, not one per created transaction.
        assertThat(telegram.sent).hasSize(1)
        assertThat(telegram.sent.single().text).contains("1 movement split into 2 transactions")
    }

    @Test
    fun `split refuses parts that do not add up to the movement total and writes nothing`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.add(movement("sp-2", today.minusDays(1), Direction.expense, "10.00", "SHOP"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()

        // A cent short — the kind of leftover a percentage split produces (3 × 33.33 % of €10).
        val oneCentShort = listOf("3.33", "3.33", "3.33").map { SplitPartRequest(BigDecimal(it), expenseCat) }
        assertThatThrownBy { pendingService.split(household.id, item.id, SplitMovementRequest(oneCentShort), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("BANK_SPLIT_TOTAL_MISMATCH")

        // And a cent over.
        val oneCentOver = listOf("3.34", "3.34", "3.34").map { SplitPartRequest(BigDecimal(it), expenseCat) }
        assertThatThrownBy { pendingService.split(household.id, item.id, SplitMovementRequest(oneCentOver), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("BANK_SPLIT_TOTAL_MISMATCH")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
        assertThat(links.findAllByPendingMovementId(item.id)).isEmpty()
    }

    @Test
    fun `split refuses a single part, a non-positive amount, a missing category and a processed item`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val incomeCat = categories.listForHousehold(household.id).first { it.kind == "income" }.code
        fake.movements.add(movement("sp-3", today.minusDays(1), Direction.expense, "10.00", "SHOP"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()

        // One part is an ordinary confirm, not a split.
        assertThatThrownBy {
            pendingService.split(household.id, item.id, SplitMovementRequest(listOf(SplitPartRequest(BigDecimal("10.00"), expenseCat))), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_SPLIT_TOO_FEW_PARTS")

        // Amounts must be positive, even when the pair still sums to the total.
        assertThatThrownBy {
            pendingService.split(
                household.id,
                item.id,
                SplitMovementRequest(listOf(SplitPartRequest(BigDecimal("15.00"), expenseCat), SplitPartRequest(BigDecimal("-5.00"), expenseCat))),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_SPLIT_AMOUNT_INVALID")

        // Same category rule as Confirm: every part needs one.
        assertThatThrownBy {
            pendingService.split(
                household.id,
                item.id,
                SplitMovementRequest(listOf(SplitPartRequest(BigDecimal("5.00"), expenseCat), SplitPartRequest(BigDecimal("5.00"), " "))),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_CATEGORY_REQUIRED")

        // The parts' categories are validated against the split's direction like any other write path.
        assertThatThrownBy {
            pendingService.split(
                household.id,
                item.id,
                SplitMovementRequest(listOf(SplitPartRequest(BigDecimal("5.00"), expenseCat), SplitPartRequest(BigDecimal("5.00"), incomeCat))),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("CATEGORY_DIRECTION_MISMATCH")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()

        // Once confirmed the item is terminal — it can't be split afterwards.
        pendingService.confirm(household.id, item.id, ConfirmMovementRequest(categoryCode = expenseCat), user)
        assertThatThrownBy {
            pendingService.split(
                household.id,
                item.id,
                SplitMovementRequest(listOf(SplitPartRequest(BigDecimal("5.00"), expenseCat), SplitPartRequest(BigDecimal("5.00"), expenseCat))),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("PENDING_MOVEMENT_ALREADY_PROCESSED")
    }

    @Test
    fun `a transaction created by a split cannot be claimed by another movement`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        // The €30 part below looks exactly like this second movement, so it becomes a replace candidate.
        fake.movements.addAll(
            listOf(
                movement("sp-4a", today.minusDays(1), Direction.expense, "120.00", "SUPERMARKET"),
                movement("sp-4b", today.minusDays(1), Direction.expense, "30.00", "SUPERMARKET"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        val big = items.first { it.amount.compareTo(BigDecimal("120.00")) == 0 }
        val small = items.first { it.amount.compareTo(BigDecimal("30.00")) == 0 }

        val split = pendingService.split(
            household.id,
            big.id,
            SplitMovementRequest(
                listOf(
                    SplitPartRequest(BigDecimal("90.00"), expenseCat),
                    SplitPartRequest(BigDecimal("30.00"), expenseCat),
                ),
            ),
            user,
        )
        val thirtyPart = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
            .first { it.amount.compareTo(BigDecimal("30.00")) == 0 }
        assertThat(split.createdTransactionIds).contains(thirtyPart.id)

        // The candidate surfaces for the other movement but is flagged, and claiming it is refused —
        // the single created_transaction_id is null here, so only the link table can know this.
        val candidates = pendingService.duplicateCandidates(household.id, small.id)
        assertThat(candidates.map { it.transactionId }).contains(thirtyPart.id)
        assertThat(candidates.first { it.transactionId == thirtyPart.id }.bankLinked).isTrue()
        assertThatThrownBy {
            pendingService.replace(household.id, small.id, ReplaceTransactionRequest(transactionId = thirtyPart.id), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("TRANSACTION_ALREADY_BANK_LINKED")
    }

    @Test
    fun `merging two same-way items nets to their sum, links both, and learns no rule`() {
        val (user, household) = seed()
        seedTelegram(household.id, user.id)
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        // One dinner billed as a card charge plus a tip taken two days later.
        fake.movements.addAll(
            listOf(
                movement("mg-1a", today.minusDays(3), Direction.expense, "12.50", "TAPAS"),
                movement("mg-1b", today.minusDays(1), Direction.expense, "7.50", "TIP"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        // The ingest announces itself ("new movements to review"); what follows is only about the merge.
        telegram.sent.clear()

        val merged = pendingService.merge(
            household.id,
            MergeMovementsRequest(items = mergeItems(items), categoryCode = expenseCat),
            user,
        )

        assertThat(merged.mergedCount).isEqualTo(2)
        assertThat(pendingService.pendingCount(household.id)).isZero()

        // Exactly one transaction, carrying the sum on the earliest item's date.
        val created = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
        assertThat(created).hasSize(1)
        val tx = created.single()
        assertThat(tx.id).isEqualTo(merged.transactionId)
        assertThat(tx.amount).isEqualByComparingTo("20.00")
        assertThat(tx.occurrenceDate).isEqualTo(today.minusDays(3))
        assertThat(tx.direction).isEqualTo(Direction.expense)
        assertThat(tx.categoryCode).isEqualTo(expenseCat)
        assertThat(tx.description).isEqualTo("TAPAS – Test TAPAS + TIP – Test TIP")

        // Both items are confirmed and linked to that one transaction; as a split, none owns it alone.
        val after = pending.findAllByIdInAndHouseholdId(items.map { it.id }, household.id)
        assertThat(after).allMatch { it.status == MovementStatus.confirmed }
        assertThat(after).allMatch { it.createdTransactionId == null }
        assertThat(after).allMatch { it.suggestedCategoryCode == expenseCat }
        val linked = links.findAllByPendingMovementIdIn(items.map { it.id })
        assertThat(linked).hasSize(2)
        assertThat(linked.map { it.transactionId }).containsOnly(tx.id)

        // Two counterparties funding one purchase is not a counterparty -> category mapping.
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)).isEmpty()

        // One aggregated notification, not a create card for the transaction the merge produced.
        assertThat(telegram.sent).hasSize(1)
        assertThat(telegram.sent.single().text).contains("2 movements merged into 1 transaction")
    }

    @Test
    fun `merging three items nets to the cent and honours an explicit date and description`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.addAll(
            listOf(
                movement("mg-2a", today.minusDays(2), Direction.expense, "3.33", "BAR"),
                movement("mg-2b", today.minusDays(1), Direction.expense, "3.33", "BAR"),
                movement("mg-2c", today.minusDays(1), Direction.expense, "3.34", "BAR"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        val merged = pendingService.merge(
            household.id,
            MergeMovementsRequest(
                items = mergeItems(items),
                categoryCode = expenseCat,
                // A date none of the items carries: the picker allows any, not just the ones present.
                date = today.minusDays(5),
                description = "Rounds",
            ),
            user,
        )

        assertThat(merged.mergedCount).isEqualTo(3)
        val created = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
        assertThat(created).hasSize(1)
        val tx = created.single()
        assertThat(tx.amount).isEqualByComparingTo("10.00")
        assertThat(tx.occurrenceDate).isEqualTo(today.minusDays(5))
        assertThat(tx.description).isEqualTo("Rounds")
        assertThat(links.findAllByPendingMovementIdIn(items.map { it.id }).map { it.transactionId })
            .hasSize(3).containsOnly(tx.id)
    }

    @Test
    fun `merging a charge and its partial refund nets to the expense that actually left the account`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.addAll(
            listOf(
                movement("mg-6a", today.minusDays(2), Direction.expense, "9.03", "PHARMACY"),
                movement("mg-6b", today.minusDays(1), Direction.income, "7.78", "PHARMACY"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        val merged = pendingService.merge(
            household.id,
            MergeMovementsRequest(items = mergeItems(items), categoryCode = expenseCat),
            user,
        )

        // −9.03 + 7.78 = −1.25: an expense of 1.25, not a sum of 16.81.
        val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today).single()
        assertThat(tx.id).isEqualTo(merged.transactionId)
        assertThat(tx.direction).isEqualTo(Direction.expense)
        assertThat(tx.amount).isEqualByComparingTo("1.25")
        assertThat(tx.occurrenceDate).isEqualTo(today.minusDays(2))
        assertThat(links.findAllByPendingMovementIdIn(items.map { it.id }).map { it.transactionId })
            .hasSize(2).containsOnly(tx.id)
        assertThat(pendingService.pendingCount(household.id)).isZero()
    }

    @Test
    fun `a merge where the money coming in wins nets to an income, and the category follows that direction`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val incomeCat = categories.listForHousehold(household.id).first { it.kind == "income" }.code
        fake.movements.addAll(
            listOf(
                movement("mg-7a", today.minusDays(3), Direction.income, "30.00", "EMPLOYER"),
                movement("mg-7b", today.minusDays(1), Direction.expense, "12.00", "EMPLOYER"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        // The netted direction is what the category is validated against, even though an expense is in the mix.
        assertThatThrownBy {
            pendingService.merge(household.id, MergeMovementsRequest(mergeItems(items), expenseCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("CATEGORY_DIRECTION_MISMATCH")
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)

        pendingService.merge(household.id, MergeMovementsRequest(mergeItems(items), incomeCat), user)

        val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today).single()
        assertThat(tx.direction).isEqualTo(Direction.income)
        assertThat(tx.amount).isEqualByComparingTo("18.00")
        assertThat(tx.categoryCode).isEqualTo(incomeCat)
    }

    @Test
    fun `a direction sent with an item decides its sign in the net, overriding the ingested one`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        // The bank booked the refund as a charge; the inbox row was flipped to income before merging.
        fake.movements.addAll(
            listOf(
                movement("mg-8a", today.minusDays(2), Direction.expense, "20.00", "SHOP"),
                movement("mg-8b", today.minusDays(1), Direction.expense, "5.00", "SHOP"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        val misbooked = items.first { it.amount.compareTo(BigDecimal("5.00")) == 0 }
        val charge = items.first { it.id != misbooked.id }

        pendingService.merge(
            household.id,
            MergeMovementsRequest(
                items = listOf(MergeItemRequest(charge.id), MergeItemRequest(misbooked.id, Direction.income)),
                categoryCode = expenseCat,
            ),
            user,
        )

        // −20.00 + 5.00 = −15.00; stored directions alone would have made it an expense of 25.00.
        val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today).single()
        assertThat(tx.direction).isEqualTo(Direction.expense)
        assertThat(tx.amount).isEqualByComparingTo("15.00")
    }

    @Test
    fun `merge refuses too few items, a missing category and unknown ids, writing nothing`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.addAll(
            listOf(
                movement("mg-3a", today.minusDays(1), Direction.expense, "10.00", "SHOP"),
                movement("mg-3b", today.minusDays(1), Direction.expense, "5.00", "SHOP"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        // One item is an ordinary confirm, not a merge — and repeating an id doesn't make two.
        assertThatThrownBy {
            pendingService.merge(household.id, MergeMovementsRequest(listOf(MergeItemRequest(items[0].id)), expenseCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_MERGE_TOO_FEW_ITEMS")
        assertThatThrownBy {
            pendingService.merge(
                household.id,
                MergeMovementsRequest(listOf(MergeItemRequest(items[0].id), MergeItemRequest(items[0].id)), expenseCat),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_MERGE_TOO_FEW_ITEMS")

        // Same category rule as Confirm: the merged transaction needs one.
        assertThatThrownBy {
            pendingService.merge(household.id, MergeMovementsRequest(mergeItems(items), " "), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_CATEGORY_REQUIRED")

        assertThatThrownBy {
            pendingService.merge(
                household.id,
                MergeMovementsRequest(listOf(MergeItemRequest(items[0].id), MergeItemRequest(UUID.randomUUID())), expenseCat),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("PENDING_MOVEMENT_NOT_FOUND")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
        assertThat(links.findAllByPendingMovementIdIn(items.map { it.id })).isEmpty()
    }

    @Test
    fun `merge refuses a selection that cancels out, since there is no transaction to create`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.addAll(
            listOf(
                movement("mg-9a", today.minusDays(2), Direction.expense, "25.00", "HOTEL"),
                movement("mg-9b", today.minusDays(1), Direction.income, "25.00", "HOTEL"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        assertThatThrownBy {
            pendingService.merge(household.id, MergeMovementsRequest(mergeItems(items), expenseCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_MERGE_NETS_TO_ZERO")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
        assertThat(links.findAllByPendingMovementIdIn(items.map { it.id })).isEmpty()
    }

    @Test
    fun `cancelling out rejects every item, creates nothing, and leaves them restorable`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.addAll(
            listOf(
                movement("mg-10a", today.minusDays(2), Direction.expense, "25.00", "HOTEL"),
                movement("mg-10b", today.minusDays(1), Direction.income, "25.00", "HOTEL"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        val result = pendingService.cancelOut(household.id, CancelOutRequest(mergeItems(items)), user)

        assertThat(result.rejected).isEqualTo(2)
        assertThat(pendingService.pendingCount(household.id)).isZero()
        val after = pending.findAllByIdInAndHouseholdId(items.map { it.id }, household.id)
        assertThat(after).allMatch { it.status == MovementStatus.rejected }
        assertThat(after).allMatch { it.createdTransactionId == null }
        // Nothing was created, so there is nothing to link them to either.
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
        assertThat(links.findAllByPendingMovementIdIn(items.map { it.id })).isEmpty()

        // Ordinary rejected items: the rejected filter can send them back to the inbox.
        assertThat(pendingService.restoreBatch(household.id, items.map { it.id }).restored).isEqualTo(2)
        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)
    }

    @Test
    fun `cancelling out refuses items that do not cancel out and writes nothing`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        fake.movements.addAll(
            listOf(
                movement("mg-11a", today.minusDays(2), Direction.expense, "25.00", "HOTEL"),
                movement("mg-11b", today.minusDays(1), Direction.income, "24.00", "HOTEL"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        assertThatThrownBy {
            pendingService.cancelOut(household.id, CancelOutRequest(mergeItems(items)), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_MERGE_NOT_CANCELLING_OUT")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)
    }

    @Test
    fun `a failing merge leaves every item pending and creates nothing`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val incomeCat = categories.listForHousehold(household.id).first { it.kind == "income" }.code
        fake.movements.addAll(
            listOf(
                movement("mg-4a", today.minusDays(1), Direction.expense, "10.00", "SHOP"),
                movement("mg-4b", today.minusDays(1), Direction.expense, "5.00", "SHOP"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content

        // The transaction create is the last thing to fail, well after the items passed validation.
        assertThatThrownBy {
            pendingService.merge(household.id, MergeMovementsRequest(mergeItems(items), incomeCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("CATEGORY_DIRECTION_MISMATCH")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(2)
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).isEmpty()
        assertThat(links.findAllByPendingMovementIdIn(items.map { it.id })).isEmpty()

        // A merge is all-or-nothing: one already-processed item aborts it instead of being skipped.
        pendingService.confirm(household.id, items[0].id, ConfirmMovementRequest(categoryCode = expenseCat), user)
        assertThatThrownBy {
            pendingService.merge(household.id, MergeMovementsRequest(mergeItems(items), expenseCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("PENDING_MOVEMENT_ALREADY_PROCESSED")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
        // Only the single confirm's transaction exists, and the survivor is still unlinked.
        assertThat(transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)).hasSize(1)
        assertThat(links.findAllByPendingMovementId(items[1].id)).isEmpty()
    }

    @Test
    fun `a transaction created by a merge cannot be claimed by another movement`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        // The merged total (€120) looks exactly like the third movement, so it becomes a replace candidate.
        fake.movements.addAll(
            listOf(
                movement("mg-5a", today.minusDays(1), Direction.expense, "90.00", "SUPERMARKET"),
                movement("mg-5b", today.minusDays(1), Direction.expense, "30.00", "SUPERMARKET"),
                movement("mg-5c", today.minusDays(1), Direction.expense, "120.00", "SUPERMARKET"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        val parts = items.filter { it.amount.compareTo(BigDecimal("120.00")) != 0 }
        val lookalike = items.first { it.amount.compareTo(BigDecimal("120.00")) == 0 }

        val merged = pendingService.merge(household.id, MergeMovementsRequest(mergeItems(parts), expenseCat), user)

        // Relaxing V032's unique index left this check as the only guard, and it still holds: the
        // candidate surfaces flagged, and claiming it is refused.
        val candidates = pendingService.duplicateCandidates(household.id, lookalike.id)
        assertThat(candidates.map { it.transactionId }).contains(merged.transactionId)
        assertThat(candidates.first { it.transactionId == merged.transactionId }.bankLinked).isTrue()
        assertThatThrownBy {
            pendingService.replace(household.id, lookalike.id, ReplaceTransactionRequest(transactionId = merged.transactionId), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("TRANSACTION_ALREADY_BANK_LINKED")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
    }

    @Test
    fun `confirming an income item as a refund creates a negative expense linked to the purchase`() {
        val (user, household) = seed()
        seedTelegram(household.id, user.id)
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        val original = manualTransaction(household, user, today.minusDays(10), expenseCat, "80.00", "Jacket")
        fake.movements.add(movement("rf-1", today.minusDays(1), Direction.income, "30.00", "SHOP"))
        link(household, user)
        val item = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content.single()
        telegram.sent.clear()

        val confirmed = pendingService.confirmAsRefund(
            household.id,
            item.id,
            ConfirmAsRefundRequest(categoryCode = expenseCat, refundOfTransactionId = original.id),
            user,
        )

        assertThat(confirmed.status).isEqualTo(MovementStatus.confirmed)
        assertThat(pendingService.pendingCount(household.id)).isZero()

        // The bank called it income; the ledger records money coming back out of the category it left.
        val refund = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, today.minusDays(30), today)
            .first { it.isRefund }
        assertThat(refund.direction).isEqualTo(Direction.expense)
        assertThat(refund.amount).isEqualByComparingTo("-30.00")
        assertThat(refund.occurrenceDate).isEqualTo(today.minusDays(1))
        assertThat(refund.categoryCode).isEqualTo(expenseCat)
        assertThat(refund.refundOfTransactionId).isEqualTo(original.id)

        // Linked like any other confirm, so the transaction can't later be claimed by another movement.
        assertThat(confirmed.createdTransactionId).isEqualTo(refund.id)
        assertThat(links.findAllByPendingMovementId(item.id).map { it.transactionId }).containsExactly(refund.id)

        // A refund's counterparty says nothing about what the household usually spends there.
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household.id)).isEmpty()

        // It rides the ordinary transaction card, like a single confirm does.
        assertThat(telegram.sent).hasSize(1)
        assertThat(telegram.sent.single().text).contains("Transaction created")
    }

    @Test
    fun `only an income item can become a refund, and only while it is pending`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val expenseCat = expenseCategory(household)
        fake.movements.addAll(
            listOf(
                movement("rf-2a", today.minusDays(1), Direction.expense, "20.00", "SHOP"),
                movement("rf-2b", today.minusDays(1), Direction.income, "15.00", "SHOP"),
            ),
        )
        link(household, user)
        val items = pending.search(household.id, MovementStatus.pending, null, null, null, PageRequest.of(0, 100)).content
        val outgoing = items.first { it.direction == Direction.expense }
        val incoming = items.first { it.direction == Direction.income }

        // Money going back out is an ordinary expense, not a refund.
        assertThatThrownBy {
            pendingService.confirmAsRefund(household.id, outgoing.id, ConfirmAsRefundRequest(expenseCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_REFUND_REQUIRES_INCOME")

        pendingService.confirmAsRefund(household.id, incoming.id, ConfirmAsRefundRequest(expenseCat), user)
        assertThatThrownBy {
            pendingService.confirmAsRefund(household.id, incoming.id, ConfirmAsRefundRequest(expenseCat), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("PENDING_MOVEMENT_ALREADY_PROCESSED")

        assertThat(pendingService.pendingCount(household.id)).isEqualTo(1)
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

    /** Without settings the notification listener bails out before formatting anything. */
    private fun seedTelegram(householdId: UUID, userId: UUID) {
        telegramSettings.save(
            TelegramSettings(
                householdId = householdId,
                chatId = "chat-123",
                botTokenEnc = telegramCrypto.encrypt("bot-token-xyz"),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )
    }

    /** Merge and cancel-out take a direction per item; the tests that don't flip one send the stored one. */
    private fun mergeItems(movements: List<PendingMovement>): List<MergeItemRequest> =
        movements.map { MergeItemRequest(it.id) }

    private fun expenseCategory(household: Household): String =
        categories.listForHousehold(household.id).first { it.kind == "expense" }.code

    /** The "existing" side of a replace, saved straight through the repository. */
    private fun manualTransaction(
        household: Household,
        user: User,
        date: LocalDate,
        categoryCode: String,
        amount: String,
        description: String,
        recurringTemplateId: UUID? = null,
    ) = transactions.save(
        Transaction(
            householdId = household.id,
            occurrenceDate = date,
            direction = Direction.expense,
            categoryCode = categoryCode,
            amount = BigDecimal(amount),
            description = description,
            recurringTemplateId = recurringTemplateId,
            createdByUserId = user.id,
            updatedByUserId = user.id,
        ),
    )

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
        credentialsService.save(household.id, TEST_APP_ID, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        return user to household
    }

    private companion object {
        const val TEST_APP_ID = "test-app"
    }
}
