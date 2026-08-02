package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.sync.BankSyncService
import com.sephilabs.sharedledger.bank.sync.SyncMode
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper

/** Per-household credentials: what may be pasted, what the API returns, and what happens to connections
 *  when the configured application changes. A bank ties its consent to one application, so syncing under a
 *  different one is impossible and must be *said*, not silently attempted. */
@ResourceLock("fake-bank-connector")
class BankCredentialsIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val credentials: BankCredentialsService,
    private val credentialsRepo: BankCredentialsRepository,
    private val bankService: BankService,
    private val syncService: BankSyncService,
    private val connections: BankConnectionRepository,
    private val syncRuns: BankSyncRunRepository,
    private val callbackUrl: BankCallbackUrl,
    private val objectMapper: ObjectMapper,
    private val fake: FakeBankConnector,
) : IntegrationTestBase() {

    /** `redirect-url` is unset under the test profile, so linking exercises the request-derived fallback and
     *  needs a bound request. [BankCallbackUrlTest] covers the configured path. */
    @BeforeEach
    fun bindRequest() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        fake.lastState = null
        fake.fetchCalls.clear()
        fake.movements.clear()
    }

    @AfterEach
    fun unbindRequest() {
        RequestContextHolder.resetRequestAttributes()
    }

    // --- Validation & storage -----------------------------------------------------------------

    @Test
    fun `a full PEM paste and a bare base64 paste store the same key`() {
        val (user, household) = seed()

        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Pem, confirm = false, byUserId = user.id)
        val fromPem = credentials.resolve(household.id)!!.privateKeyBase64

        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        val fromBase64 = credentials.resolve(household.id)!!.privateKeyBase64

        assertThat(fromPem).isEqualTo(BankTestKeys.pkcs8Base64)
        assertThat(fromBase64).isEqualTo(BankTestKeys.pkcs8Base64)
    }

    @Test
    fun `a PKCS#1 key is rejected with its own error, and nothing is stored`() {
        val (user, household) = seed()

        assertThatThrownBy {
            credentials.save(household.id, APP_A, pkcs1Pem(), confirm = false, byUserId = user.id)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_PRIVATE_KEY_PKCS1")

        assertThat(credentialsRepo.findByHouseholdId(household.id)).isNull()
    }

    @Test
    fun `an unparseable key is rejected as invalid`() {
        val (user, household) = seed()

        assertThatThrownBy {
            credentials.save(household.id, APP_A, "not a key at all", confirm = false, byUserId = user.id)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_PRIVATE_KEY_INVALID")
    }

    @Test
    fun `the first save requires a key, later saves may omit it to keep the stored one`() {
        val (user, household) = seed()

        assertThatThrownBy {
            credentials.save(household.id, APP_A, null, confirm = false, byUserId = user.id)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_PRIVATE_KEY_REQUIRED")

        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        credentials.save(household.id, APP_B, null, confirm = false, byUserId = user.id)

        val stored = credentials.resolve(household.id)!!
        assertThat(stored.appId).isEqualTo(APP_B)
        assertThat(stored.privateKeyBase64).isEqualTo(BankTestKeys.pkcs8Base64)
    }

    @Test
    fun `the stored key is encrypted at rest, never held in the clear`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)

        val row = credentialsRepo.findByHouseholdId(household.id)!!
        assertThat(row.privateKeyEnc).isNotEqualTo(BankTestKeys.pkcs8Base64)
        assertThat(row.privateKeyEnc).doesNotContain(BankTestKeys.pkcs8Base64.take(32))
    }

    @Test
    fun `the redirect URL is derived from the request and matches what linking sends`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)

        bankService.startLink(
            household.id,
            StartLinkRequest(aspspName = "ING", country = "NL"),
            user,
            HouseholdRole.owner,
        )

        assertThat(callbackUrl.current()).endsWith("/settings/banks/callback")
        assertThat(fake.lastRedirectUrl).isEqualTo(callbackUrl.current())
    }

    /** Jackson cannot map an absent field onto a Kotlin primitive, so an omitted `confirm` fails before the
     *  request reaches the controller. */
    @Test
    fun `omitting confirm in the request body is accepted`() {
        val body = objectMapper.readValue(
            """{"appId":"$APP_A","privateKey":"${BankTestKeys.pkcs8Base64}"}""",
            BankCredentialsUpdateRequest::class.java,
        )

        assertThat(body.confirm).isNull()
        assertThat(body.appId).isEqualTo(APP_A)
    }

    // --- Identity anchoring -------------------------------------------------------------------

    @Test
    fun `changing the application id warns about the connections it would strand, then saves on confirm`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        link(household, user)

        // Same application: nothing is stranded, so no warning.
        credentials.save(household.id, APP_A, null, confirm = false, byUserId = user.id)

        assertThatThrownBy {
            credentials.save(household.id, APP_B, null, confirm = false, byUserId = user.id)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_CREDENTIALS_APP_ID_CHANGED")
        assertThat(credentials.resolve(household.id)!!.appId).isEqualTo(APP_A)

        credentials.save(household.id, APP_B, null, confirm = true, byUserId = user.id)
        assertThat(credentials.resolve(household.id)!!.appId).isEqualTo(APP_B)
    }

    /** Honest immediately, not after the next scheduled sync — that gap is the post-migration window where the
     *  user needs telling. */
    @Test
    fun `the listed status reports the credential state before any sync has run`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        link(household, user)
        assertThat(connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().status)
            .isEqualTo(ConnectionStatus.active)

        credentials.save(household.id, APP_B, null, confirm = true, byUserId = user.id)
        assertThat(bankService.listConnections(household.id, user, HouseholdRole.owner).single().status)
            .isEqualTo(ConnectionStatus.credentials_mismatch)

        credentialsRepo.delete(credentialsRepo.findByHouseholdId(household.id)!!)
        assertThat(bankService.listConnections(household.id, user, HouseholdRole.owner).single().status)
            .isEqualTo(ConnectionStatus.credentials_required)

        // The stored status is untouched — this is a display rule; the sync gate persists it.
        assertThat(connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().status)
            .isEqualTo(ConnectionStatus.active)
    }

    @Test
    fun `a connection is stamped with the application it was linked under`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        link(household, user)

        assertThat(connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single().appId).isEqualTo(APP_A)
    }

    @Test
    fun `sync parks a connection when the household has no credentials, and resumes once they return`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        link(household, user)
        val connection = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single()
        val runsAfterLink = syncRuns.findAll().count { it.connectionId == connection.id }

        credentialsRepo.delete(credentialsRepo.findByHouseholdId(household.id)!!)
        syncService.sync(connection.id, SyncMode.SCHEDULED, null)

        assertThat(connections.findById(connection.id).get().status)
            .isEqualTo(ConnectionStatus.credentials_required)
        // Parked, not failed: no run is recorded, so the UI shows no error noise.
        assertThat(syncRuns.findAll().count { it.connectionId == connection.id }).isEqualTo(runsAfterLink)

        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        syncService.sync(connection.id, SyncMode.SCHEDULED, null)

        assertThat(connections.findById(connection.id).get().status).isEqualTo(ConnectionStatus.active)
    }

    @Test
    fun `sync refuses a connection authorized under a different application until it is re-linked`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        link(household, user)
        val connection = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single()

        credentials.save(household.id, APP_B, null, confirm = true, byUserId = user.id)
        syncService.sync(connection.id, SyncMode.SCHEDULED, null)

        assertThat(connections.findById(connection.id).get().status)
            .isEqualTo(ConnectionStatus.credentials_mismatch)

        // Re-linking under the current credentials re-stamps it and it syncs again.
        bankService.startLink(
            household.id,
            StartLinkRequest(aspspName = "ING", country = "NL", relinkConnectionId = connection.id),
            user,
            HouseholdRole.owner,
        )
        bankService.completeLink(
            household.id,
            CompleteLinkRequest(code = "code", state = fake.lastState!!),
            user,
            HouseholdRole.owner,
        )

        val relinked = connections.findById(connection.id).get()
        assertThat(relinked.appId).isEqualTo(APP_B)
        assertThat(relinked.status).isEqualTo(ConnectionStatus.active)
    }

    @Test
    fun `a connection the migration could not attribute asks for a re-link rather than being re-routed`() {
        val (user, household) = seed()
        credentials.save(household.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = user.id)
        link(household, user)
        val connection = connections.findAllByHouseholdIdOrderByCreatedAtAsc(household.id).single()

        // What V030 leaves behind when the old env app id was absent at deploy time.
        connections.save(connection.apply { appId = null })
        syncService.sync(connection.id, SyncMode.SCHEDULED, null)

        assertThat(connections.findById(connection.id).get().status)
            .isEqualTo(ConnectionStatus.credentials_mismatch)
    }

    // --- Feature gating -----------------------------------------------------------------------

    @Test
    fun `without credentials the config reports the feature off and linking is refused`() {
        val (user, household) = seed()

        val config = bankService.config(household.id)
        assertThat(config.credentialsConfigured).isFalse()
        assertThat(config.nextSyncTimes).isEmpty()

        assertThatThrownBy {
            bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL"), user, HouseholdRole.owner)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("BANK_CREDENTIALS_REQUIRED")

        assertThatThrownBy { bankService.listAspsps(household.id, "NL") }
            .isInstanceOf(AppException::class.java).hasMessageContaining("BANK_CREDENTIALS_REQUIRED")
    }

    @Test
    fun `credentials are scoped to one household`() {
        val (userA, householdA) = seed()
        val (_, householdB) = seed()
        credentials.save(householdA.id, APP_A, BankTestKeys.pkcs8Base64, confirm = false, byUserId = userA.id)

        assertThat(credentials.resolve(householdA.id)).isNotNull()
        assertThat(credentials.resolve(householdB.id)).isNull()
        assertThat(bankService.config(householdB.id).credentialsConfigured).isFalse()
    }

    private fun link(household: Household, user: User) {
        bankService.startLink(household.id, StartLinkRequest(aspspName = "ING", country = "NL", label = "Test"), user, HouseholdRole.owner)
        bankService.completeLink(household.id, CompleteLinkRequest(code = "code", state = fake.lastState!!), user, HouseholdRole.owner)
    }

    private fun pkcs1Pem(): String = buildString {
        append("-----BEGIN RSA PRIVATE KEY-----\n")
        BankTestKeys.pkcs1Base64.chunked(64).forEach { append(it).append('\n') }
        append("-----END RSA PRIVATE KEY-----\n")
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "creds${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }

    private companion object {
        const val APP_A = "application-a"
        const val APP_B = "application-b"
    }
}
