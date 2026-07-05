package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.Aspsp
import com.sephilabs.sharedledger.bank.connector.AuthStart
import com.sephilabs.sharedledger.bank.connector.AuthStartRequest
import com.sephilabs.sharedledger.bank.connector.AuthorizedAccount
import com.sephilabs.sharedledger.bank.connector.AuthorizedSession
import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.BankMovement
import com.sephilabs.sharedledger.bank.connector.ConsentStatus
import com.sephilabs.sharedledger.bank.connector.MovementPage
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

    override fun listAspsps(country: String): List<Aspsp> = aspsps.filter { it.country == country }

    override fun startAuthorization(request: AuthStartRequest): AuthStart {
        lastState = request.state
        return AuthStart(url = "https://bank.example/sca?state=${request.state}", authorizationId = "auth-1")
    }

    override fun completeAuthorization(code: String): AuthorizedSession =
        AuthorizedSession(sessionId = sessionId, accounts = accounts, consentExpiresAt = consentExpiresAt)

    override fun sessionStatus(sessionId: String): ConsentStatus = status

    override fun fetchMovements(
        sessionId: String,
        accountUid: String,
        dateFrom: LocalDate,
        continuationKey: String?,
    ): MovementPage =
        MovementPage(movements = movements.filter { !it.bookingDate.isBefore(dateFrom) }, continuationKey = null)
}

@TestConfiguration
class FakeBankConnectorConfig {
    @Bean
    @Primary
    fun fakeBankConnector(): FakeBankConnector = FakeBankConnector()
}
