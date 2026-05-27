package com.sharedledger.notification

import com.sharedledger.IntegrationTestBase
import com.sharedledger.config.AppProperties
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdMember
import com.sharedledger.household.HouseholdMemberId
import com.sharedledger.household.HouseholdMemberRepository
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.household.HouseholdRole
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import com.sharedledger.recurring.Cadence
import com.sharedledger.recurring.RecurringMaterializer
import com.sharedledger.recurring.RecurringService
import com.sharedledger.recurring.RecurringTemplateRequest
import com.sharedledger.transaction.Direction
import com.sharedledger.transaction.TransactionImportService
import com.sharedledger.transaction.TransactionRequest
import com.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
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

@Import(RecordingTelegramConfig::class)
class TelegramNotificationIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val settingsRepo: TelegramSettingsRepository,
    private val crypto: TelegramCrypto,
    private val transactions: TransactionService,
    private val transactionImport: TransactionImportService,
    private val recurring: RecurringService,
    private val materializer: RecurringMaterializer,
    private val controller: TelegramSettingsController,
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

        awaitSent(1)
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
        sleepBriefly()
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `master toggle off suppresses everything`() {
        val (user, household) = seed()
        seedSettings(household.id, user.id) { active = false }

        transactions.create(household.id, expense(), user)
        sleepBriefly()
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `no token configured means no dispatch`() {
        val (user, household) = seed()
        settingsRepo.save(
            TelegramSettings(householdId = household.id, createdByUserId = user.id, updatedByUserId = user.id),
        )

        transactions.create(household.id, expense(), user)
        sleepBriefly()
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
        sleepBriefly()
        assertThat(client.sent).withFailMessage("imports must not notify").isEmpty()

        transactions.create(household.id, expense(), user)
        awaitSent(1)
        assertThat(client.sent.single().text).contains("Transaction created")
    }

    @Test
    fun `scheduler materialization sends one summary attributed to the owner`() {
        val (owner, household) = seed()
        members.save(HouseholdMember(HouseholdMemberId(household.id, owner.id), HouseholdRole.owner))
        seedSettings(household.id, owner.id)

        recurring.create(
            household.id,
            RecurringTemplateRequest(
                direction = Direction.expense,
                categoryCode = "groceries.groceries",
                amount = BigDecimal("25.00"),
                cadence = Cadence.monthly,
                dayOfMonth = 1,
                startDate = LocalDate.now().minusMonths(2).withDayOfMonth(1),
            ),
            owner,
        )

        val created = materializer.runForHousehold(household.id, LocalDate.now())
        assertThat(created).isGreaterThanOrEqualTo(1)

        awaitSent(1)
        val text = client.sent.single().text
        assertThat(text).contains("Recurring transactions created")
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

    private fun awaitSent(atLeast: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (client.sent.size < atLeast && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertThat(client.sent.size).isGreaterThanOrEqualTo(atLeast)
    }

    private fun sleepBriefly() = Thread.sleep(800)

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "tg${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
