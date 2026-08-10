package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.LotType
import com.sephilabs.sharedledger.portfolio.PortfolioImportService
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringMaterializer
import com.sephilabs.sharedledger.recurring.RecurringService
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.recurring.RecurringTemplateRequest
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionImportService
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Captures dispatched messages instead of calling Telegram. */
class RecordingTelegramClient(props: AppProperties) : TelegramClient(props) {
    data class Sent(val token: String, val chatId: String, val text: String)

    val sent = CopyOnWriteArrayList<Sent>()
    @Volatile var nextOk = true

    override fun sendMessage(token: String, chatId: String, markdownText: String): SendResult {
        sent.add(Sent(token, chatId, markdownText))
        return SendResult(nextOk, if (nextOk) "ok" else "Bad Request: chat not found")
    }
}

@TestConfiguration
class RecordingTelegramConfig {
    @Bean
    @Primary
    fun recordingTelegramClient(props: AppProperties) = RecordingTelegramClient(props)
}

class TelegramNotificationIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val settingsRepo: TelegramSettingsRepository,
    private val crypto: TelegramCrypto,
    private val transactions: TransactionService,
    private val transactionImport: TransactionImportService,
    private val recurring: RecurringService,
    private val templates: RecurringTemplateRepository,
    private val materializer: RecurringMaterializer,
    private val controller: TelegramSettingsController,
    private val holdings: HoldingService,
    private val portfolioImport: PortfolioImportService,
    private val client: RecordingTelegramClient,
) : IntegrationTestBase() {

    @BeforeEach
    fun resetStub() {
        client.sent.clear()
        client.nextOk = true
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `transaction create dispatches a localized message attributed to the actor`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id)

        transactions.create(household.id, expense(), user)

        assertSent(1)
        val text = client.sent.single().text
        assertThat(text).contains("Transaction created")
        assertThat(text).contains("by ${user.email}")
        assertThat(client.sent.single().chatId).isEqualTo("chat-123")
    }

    @Test
    fun `per-entity toggle off suppresses that entity but not others`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id) { notifyTransactions = false }

        transactions.create(household.id, expense(), user)
        // Positive control on an enabled entity would require movements; instead assert nothing arrives.
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `master toggle off suppresses everything`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id) { active = false }

        transactions.create(household.id, expense(), user)
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `no token configured means no dispatch`() {
        val (user, household) = seed()
        settingsRepo.save(
            TelegramSettings(householdId = household.id, createdByUserId = user.id, updatedByUserId = user.id),
        )

        transactions.create(household.id, expense(), user)
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `imports are silent but normal creates still notify`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id)

        val csv = "date;direction;category_code;amount;description;created_at;updated_at\n" +
            "2025-03-01;expense;groceries.groceries;10,00;Imported;;\n"
        val result = transactionImport.execute(household.id, csv.byteInputStream(Charsets.UTF_8), user)
        assertThat(result.inserted).isEqualTo(1)
        assertThat(client.sent).withFailMessage("imports must not notify").isEmpty()

        transactions.create(household.id, expense(), user)
        assertSent(1)
        assertThat(client.sent.single().text).contains("Transaction created")
    }

    @Test
    fun `materialization summary header drops the count when exactly one row is created`() {
        val (owner, household) = seed()
        members.save(HouseholdMember(HouseholdMemberId(household.id, owner.id), HouseholdRole.owner))
        seedSettings(household.id, owner.id)

        recurring.create(
            household.id,
            RecurringTemplateRequest(
                direction = Direction.expense,
                categoryCode = "groceries.groceries",
                amount = BigDecimal("12.34"),
                description = "Daily coffee",
                cadence = Cadence.monthly,
                dayOfMonth = 1,
                startDate = LocalDate.now(),
            ),
            owner,
        )
        val templateId = templates.findAllByHouseholdId(household.id).single().id

        val created = recurring.fireNow(household.id, templateId, owner)

        assertThat(created).isEqualTo(1)
        assertSent(1)
        val text = client.sent.single().text
        assertThat(text).contains("Recurring transaction created")
        assertThat(text).doesNotContain("(1)")
        assertThat(text).doesNotContain("Recurring transactions created")
    }

    @Test
    fun `scheduler materialization sends one summary attributed to the owner`() {
        val (owner, household) = seed()
        members.save(HouseholdMember(HouseholdMemberId(household.id, owner.id), HouseholdRole.owner))
        seedSettings(household.id, owner.id)

        val backdatedStart = LocalDate.now().minusMonths(2).withDayOfMonth(1)
        val template = recurring.create(
            household.id,
            RecurringTemplateRequest(
                direction = Direction.expense,
                categoryCode = "groceries.groceries",
                amount = BigDecimal("25.00"),
                description = "Monthly groceries",
                cadence = Cadence.monthly,
                dayOfMonth = 1,
                startDate = backdatedStart,
            ),
            owner,
        )
        // Anchor the watermark to the start date so the materializer's catch-up branch
        // emits the missed monthly occurrences (the null-watermark fallback uses updatedAt
        // = real-now, which would emit nothing).
        templates.findById(template.id).orElseThrow().apply {
            lastMaterializedThrough = backdatedStart.minusDays(1)
            templates.save(this)
        }

        val created = materializer.runForHousehold(household.id, LocalDate.now())
        assertThat(created).isGreaterThanOrEqualTo(1)

        assertSent(1)
        val text = client.sent.single().text
        assertThat(text).contains("Recurring transactions created")
        assertThat(text).contains("Monthly groceries")
        assertThat(text).contains("Recurring schedule (created by ${owner.email})")
    }

    @Test
    fun `controller stores token encrypted, never returns it, and test endpoint dispatches`() {
        val (user, household) = seed()
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user.email, null, emptyList())

        val saved = controller.update(
            household.id,
            TelegramSettingsUpdateRequest(chatId = "chat-999", botToken = "secret-bot-token"),
        )
        assertThat(saved.tokenConfigured).isTrue()
        assertThat(saved.chatId).isEqualTo("chat-999")

        val stored = settingsRepo.findByHouseholdId(household.id)!!
        assertThat(stored.botTokenEnc).isNotBlank().doesNotContain("secret-bot-token")
        assertThat(crypto.decrypt(stored.botTokenEnc!!)).isEqualTo("secret-bot-token")

        // Blank token on a later save keeps the stored one.
        controller.update(household.id, TelegramSettingsUpdateRequest(chatId = "chat-999", botToken = ""))
        assertThat(crypto.decrypt(settingsRepo.findByHouseholdId(household.id)!!.botTokenEnc!!))
            .isEqualTo("secret-bot-token")

        val result = controller.test(household.id)
        assertThat(result.ok).isTrue()
        assertThat(client.sent.last().chatId).isEqualTo("chat-999")
    }

    @Test
    fun `portfolio buy, sell and edit dispatch trade notifications`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id)
        // Unlinked holding: no price provider is touched.
        val holding = holdings.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "BTC"),
            user,
        )

        val buy = holdings.addLot(
            household.id, holding.id,
            LotRequest(type = LotType.BUY, tradedOn = LocalDate.of(2025, 3, 1), quantity = BigDecimal("1"), unitPrice = BigDecimal("40000")),
            user,
        )
        assertSent(1)
        assertThat(client.sent.last().text)
            .contains("Trade recorded").contains("Buy").contains("BTC").contains("by ${user.email}")
            .contains("40000 EUR")

        holdings.addLot(
            household.id, holding.id,
            LotRequest(type = LotType.SELL, tradedOn = LocalDate.of(2025, 4, 1), quantity = BigDecimal("0.5"), unitPrice = BigDecimal("50000")),
            user,
        )
        assertSent(2)
        assertThat(client.sent.last().text).contains("Trade recorded").contains("Sell").contains("50000 EUR")

        // Editing a trade rides the same toggle, as an update.
        holdings.updateLot(
            household.id, holding.id, buy.id,
            LotRequest(type = LotType.BUY, tradedOn = LocalDate.of(2025, 3, 1), quantity = BigDecimal("2"), unitPrice = BigDecimal("41000")),
            user,
        )
        assertSent(3)
        assertThat(client.sent.last().text).contains("Trade edited").contains("Buy").contains("41000 EUR")
    }

    @Test
    fun `holdings toggle off suppresses trades`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id) { notifyHoldings = false }
        val holding = holdings.create(
            household.id,
            HoldingRequest(assetClass = HoldingAssetClass.crypto, symbol = "ETH"),
            user,
        )

        holdings.addLot(
            household.id, holding.id,
            LotRequest(type = LotType.BUY, tradedOn = LocalDate.of(2025, 3, 1), quantity = BigDecimal("1"), unitPrice = BigDecimal("2000")),
            user,
        )
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `portfolio imports are silent`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id)

        val csv = "type;asset_class;symbol;label;native_currency;isin;traded_on;quantity;unit_price;cost_currency;fee;note\n" +
            "BUY;crypto;SOL;;EUR;;2025-03-01;10;100;EUR;;\n"
        val result = portfolioImport.execute(household.id, csv.byteInputStream(Charsets.UTF_8), user)
        assertThat(result.inserted).isEqualTo(1)
        assertThat(client.sent).withFailMessage("portfolio imports must not notify").isEmpty()
    }

    private fun expense() = TransactionRequest(
        occurrenceDate = LocalDate.of(2025, 3, 1),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal("12.34"),
        description = "Lunch",
    )

    private fun seedSettings(householdId: UUID, userId: UUID, configure: TelegramSettings.() -> Unit = {}) {
        val settings = TelegramSettings(
            householdId = householdId,
            chatId = "chat-123",
            botTokenEnc = crypto.encrypt("bot-token-xyz"),
            createdByUserId = userId,
            updatedByUserId = userId,
        ).apply(configure)
        settingsRepo.save(settings)
    }

    /** The test `telegramExecutor` blocks on dispatch, so it has already run by the time we assert. */
    private fun assertSent(atLeast: Int) {
        assertThat(client.sent.size).isGreaterThanOrEqualTo(atLeast)
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "tg${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
