package com.sephilabs.sharedledger.bank.consent

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.BankCredentialsService
import com.sephilabs.sharedledger.bank.BankService
import com.sephilabs.sharedledger.bank.BankTestKeys
import com.sephilabs.sharedledger.bank.CompleteLinkRequest
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.bank.FakeBankConnector
import com.sephilabs.sharedledger.bank.StartLinkRequest
import com.sephilabs.sharedledger.bank.connector.AuthorizedAccount
import com.sephilabs.sharedledger.bank.sync.BankSyncScheduler
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.notification.RecordingTelegramClient
import com.sephilabs.sharedledger.notification.TelegramCrypto
import com.sephilabs.sharedledger.notification.TelegramSettings
import com.sephilabs.sharedledger.notification.TelegramSettingsController
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.notification.TelegramSettingsUpdateRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

// The notifier sweeps every connection in the DB, so it may message other classes' households: hold the
// recording client's lock, and only ever assert on this test's own chat.
@ResourceLock("recording-telegram")
@ResourceLock("fake-bank-connector")
class ConsentExpiryNotifierIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val connections: BankConnectionRepository,
    private val credentialsService: BankCredentialsService,
    private val bankService: BankService,
    private val notifier: ConsentExpiryNotifier,
    private val scheduler: BankSyncScheduler,
    private val fake: FakeBankConnector,
    private val telegram: RecordingTelegramClient,
    private val telegramSettings: TelegramSettingsRepository,
    private val telegramCrypto: TelegramCrypto,
    private val props: AppProperties,
    private val members: HouseholdMemberRepository,
    private val settingsController: TelegramSettingsController,
) : IntegrationTestBase() {

    private val zone get() = ZoneId.of(props.scheduler.timezone)

    @BeforeEach
    fun reset() {
        telegram.sent.clear()
        telegram.nextOk = true
        fake.movements.clear()
        fake.movementsByAccount.clear()
        fake.accounts = listOf(AuthorizedAccount("acc-1", "NL00INGB0001234567", "Checking", "EUR"))
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
    }

    @AfterEach
    fun restore() {
        fake.consentExpiresAt = Instant.now().plus(Duration.ofDays(90))
        RequestContextHolder.resetRequestAttributes()
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the daily run notifies once when a consent expires tomorrow, and not again on the next run`() {
        val household = seedHousehold()
        connections.save(connection(household, label = "Joint account", expiresAt = daysFromToday(1)))

        scheduler.notifyConsentExpiry()
        scheduler.notifyConsentExpiry()

        val sent = sentTo(household)
        assertThat(sent).hasSize(1)
        assertThat(sent.single()).contains("Bank connection needs re-linking", "*Joint account* (ING)", "expires tomorrow", "Settings > Banks")
    }

    @Test
    fun `notifies on the first run after expiry when the one-day window was missed`() {
        val household = seedHousehold()
        connections.save(connection(household, label = "ING", expiresAt = Instant.now().minus(Duration.ofDays(2))))

        notifier.notifyDue(Instant.now())

        assertThat(sentTo(household).single()).contains("*ING*:", "has expired, syncing stopped")
    }

    @Test
    fun `a connection the sync marked expired is announced even before its stored expiry date`() {
        val household = seedHousehold()
        connections.save(connection(household, status = ConnectionStatus.expired, expiresAt = daysFromToday(60)))

        notifier.notifyDue(Instant.now())

        assertThat(sentTo(household).single()).contains("has expired")
    }

    @Test
    fun `stays quiet while the consent is more than a day away`() {
        val household = seedHousehold()
        val saved = connections.save(connection(household, expiresAt = daysFromToday(2)))

        notifier.notifyDue(Instant.now())

        assertThat(sentTo(household)).isEmpty()
        assertThat(connections.findById(saved.id).get().expiryNotifiedAt).isNull()
    }

    @Test
    fun `a paused connection's expiry is still detected`() {
        val household = seedHousehold()
        connections.save(connection(household, expiresAt = daysFromToday(1)).apply { ingestionEnabled = false })

        notifier.notifyDue(Instant.now())

        assertThat(sentTo(household)).hasSize(1)
    }

    @Test
    fun `several connections due in the same run arrive as one message`() {
        val household = seedHousehold()
        connections.save(connection(household, label = "Joint account", expiresAt = daysFromToday(1)))
        connections.save(connection(household, label = "Savings", status = ConnectionStatus.expired, expiresAt = daysFromToday(30)))

        notifier.notifyDue(Instant.now())

        val sent = sentTo(household)
        assertThat(sent).hasSize(1)
        assertThat(sent.single()).contains("2 bank connections need re-linking", "Joint account", "expires tomorrow", "Savings", "has expired")
    }

    @Test
    fun `switching the bank connections toggle off in notification settings silences the notice`() {
        val user = users.save(User(email = "consent${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = seedHousehold(user)
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user.email, null, emptyList())
        connections.save(connection(household, expiresAt = daysFromToday(1)))

        val saved = settingsController.update(
            household.id,
            TelegramSettingsUpdateRequest(chatId = chatOf(household), notifyBankConnections = false),
        )
        notifier.notifyDue(Instant.now())

        assertThat(saved.notifyBankConnections).isFalse()
        assertThat(settingsController.get(household.id).notifyBankMovements).isTrue()
        assertThat(sentTo(household)).isEmpty()
    }

    @Test
    fun `re-linking clears the marker, so the next consent cycle notifies again`() {
        val user = users.save(User(email = "consent${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = seedHousehold(user)
        fake.consentExpiresAt = daysFromToday(1)
        bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL", label = "Test"), user, HouseholdRole.owner)
        val linked = bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)
        notifier.notifyDue(Instant.now())
        assertThat(connections.findById(linked.id).get().expiryNotifiedAt).isNotNull()

        // A short-lived consent again, so the new cycle is already inside the window.
        fake.consentExpiresAt = daysFromToday(1).plus(Duration.ofHours(1))
        bankService.startLink(
            household.id,
            StartLinkRequest(aspspName = "ING", country = "NL", relinkConnectionId = linked.id),
            user,
            HouseholdRole.owner,
        )
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)
        assertThat(connections.findById(linked.id).get().expiryNotifiedAt).isNull()

        notifier.notifyDue(Instant.now())

        assertThat(sentTo(household)).hasSize(2)
    }

    /** Midday of a calendar day in the scheduler zone, so "tomorrow" never straddles midnight. */
    private fun daysFromToday(days: Long): Instant =
        Instant.now().atZone(zone).toLocalDate().plusDays(days).atTime(LocalTime.NOON).atZone(zone).toInstant()

    private fun connection(
        household: Household,
        label: String? = null,
        status: ConnectionStatus = ConnectionStatus.active,
        expiresAt: Instant,
    ) = BankConnection(
        householdId = household.id,
        aspspName = "ING",
        aspspCountry = "NL",
        label = label,
        status = status,
        consentExpiresAt = expiresAt,
    )

    private fun sentTo(household: Household): List<String> =
        telegram.sent.filter { it.chatId == chatOf(household) }.map { it.text }

    private fun chatOf(household: Household) = "chat-${household.id}"

    private fun seedHousehold(
        user: User = users.save(User(email = "consent${System.nanoTime()}@example.com", passwordHash = "x", locale = "en")),
    ): Household {
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        credentialsService.save(household.id, "test-app", BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        telegramSettings.save(
            TelegramSettings(
                householdId = household.id,
                chatId = chatOf(household),
                botTokenEnc = telegramCrypto.encrypt("bot-token-xyz"),
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        return household
    }
}
