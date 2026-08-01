package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.Aspsp
import com.sephilabs.sharedledger.bank.connector.AuthStart
import com.sephilabs.sharedledger.bank.connector.AuthStartRequest
import com.sephilabs.sharedledger.bank.connector.AuthorizedAccount
import com.sephilabs.sharedledger.bank.connector.AuthorizedSession
import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.BankConnectorException
import com.sephilabs.sharedledger.bank.connector.BankMovement
import com.sephilabs.sharedledger.bank.connector.ConsentStatus
import com.sephilabs.sharedledger.bank.connector.EbCredentials
import com.sephilabs.sharedledger.bank.connector.FetchStrategy
import com.sephilabs.sharedledger.bank.connector.MovementPage
import com.sephilabs.sharedledger.bank.connector.PsuContext
import com.sephilabs.sharedledger.bank.connector.RateLimitExceededException
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory [BankConnector] for integration tests — no real HTTP, no JWT. Seed the fields, then
 * drive the services exactly as production would (link -> sync -> inbox -> confirm).
 */
class FakeBankConnector : BankConnector {
    var aspsps: List<Aspsp> = listOf(Aspsp("ING", "NL", null), Aspsp("BBVA", "ES", null))
    var lastState: String? = null
    var sessionId: String = "session-1"
    var accounts: List<AuthorizedAccount> = listOf(AuthorizedAccount("acc-1", "NL00INGB0001234567", "Checking", "EUR"))
    var consentExpiresAt: Instant? = Instant.now().plus(Duration.ofDays(90))
    var status: ConsentStatus = ConsentStatus.ACTIVE
    val movements: MutableList<BankMovement> = mutableListOf()

    /** Every fetchMovements invocation, for asserting account / strategy / window / interactivity / paging. */
    data class FetchCall(
        val accountUid: String,
        val strategy: FetchStrategy,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
        val interactive: Boolean,
        val continuationKey: String?,
    )
    val fetchCalls: MutableList<FetchCall> = mutableListOf()

    /** When non-empty, returned in order (ignoring the date window) so pagination can be scripted. */
    val scriptedPages: ArrayDeque<MovementPage> = ArrayDeque()

    /** When >= 0, throw ASPSP_RATE_LIMIT_EXCEEDED once this many fetch calls have succeeded. */
    var failWithRateLimitAfter: Int = -1

    /** When >= 0, throw a provider error ([providerErrorCode]) once this many fetch calls succeeded. */
    var failWithProviderErrorAfter: Int = -1
    var providerErrorCode: String = "ASPSP_ERROR"

    /** Account uids the ASPSP refuses with a provider error (per-account isolation tests). */
    val failingAccountUids: MutableSet<String> = mutableSetOf()

    /** When true, [failingAccountUids] fail only on `strategy=longest`, so a bounded retry succeeds. */
    var failLongestOnly: Boolean = false

    /** Which application the last call ran under, and the redirect URL it passed to `/auth`. */
    var lastCreds: EbCredentials? = null
    var lastRedirectUrl: String? = null

    override fun listAspsps(creds: EbCredentials, country: String): List<Aspsp> {
        lastCreds = creds
        return aspsps.filter { it.country == country }
    }

    override fun startAuthorization(creds: EbCredentials, request: AuthStartRequest): AuthStart {
        lastCreds = creds
        lastState = request.state
        lastRedirectUrl = request.redirectUrl
        return AuthStart(url = "https://bank.example/sca?state=${request.state}", authorizationId = "auth-1")
    }

    override fun completeAuthorization(creds: EbCredentials, code: String): AuthorizedSession {
        lastCreds = creds
        return AuthorizedSession(sessionId = sessionId, accounts = accounts, consentExpiresAt = consentExpiresAt)
    }

    override fun sessionStatus(creds: EbCredentials, sessionId: String): ConsentStatus {
        lastCreds = creds
        return status
    }

    override fun fetchMovements(
        creds: EbCredentials,
        sessionId: String,
        accountUid: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        strategy: FetchStrategy,
        continuationKey: String?,
        psu: PsuContext?,
    ): MovementPage {
        lastCreds = creds
        fetchCalls += FetchCall(accountUid, strategy, dateFrom, dateTo, psu != null, continuationKey)
        if (failWithRateLimitAfter >= 0 && fetchCalls.size > failWithRateLimitAfter) {
            throw RateLimitExceededException("ASPSP_RATE_LIMIT_EXCEEDED")
        }
        val accountRefused = accountUid in failingAccountUids &&
            (!failLongestOnly || strategy == FetchStrategy.LONGEST)
        if (accountRefused || (failWithProviderErrorAfter >= 0 && fetchCalls.size > failWithProviderErrorAfter)) {
            throw BankConnectorException("$providerErrorCode: Error interacting with ASPSP", null, providerErrorCode)
        }
        if (scriptedPages.isNotEmpty()) return scriptedPages.removeFirst()
        // Both bounds inclusive, as the provider documents them.
        val filtered = movements.filter {
            (dateFrom == null || !it.bookingDate.isBefore(dateFrom)) &&
                (dateTo == null || !it.bookingDate.isAfter(dateTo))
        }
        return MovementPage(movements = filtered, continuationKey = null)
    }
}

@TestConfiguration
class FakeBankConnectorConfig {
    @Bean
    @Primary
    fun fakeBankConnector(): FakeBankConnector = FakeBankConnector()
}
