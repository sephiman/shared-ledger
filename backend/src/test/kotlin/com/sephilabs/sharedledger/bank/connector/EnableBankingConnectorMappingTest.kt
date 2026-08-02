package com.sephilabs.sharedledger.bank.connector

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.KeyPairGenerator
import java.time.LocalDate
import java.util.Base64

/** Covers what [com.sephilabs.sharedledger.bank.FakeBankConnector] bypasses: the JSON → [BankMovement]
 *  mapping and the provider's error bodies. Drives the real connector over a mocked HTTP layer so ASPSP
 *  quirks (ING pending entries, Bankinter's `ASPSP_ERROR`) are exercised end-to-end. */
@ResourceLock("fake-bank-connector")
class EnableBankingConnectorMappingTest {

    private val props = AppProperties(
        enableBanking = AppProperties.EnableBanking(
            baseUrl = "https://api.enablebanking.test",
            minRequestIntervalMs = 0,
        ),
    )

    // A real (throwaway) RSA key so EnableBankingJwt.bearer() signs for real; credentials are
    // per-household now, so they arrive as a call argument rather than from configuration.
    private val creds = EbCredentials(appId = "test-app", privateKeyBase64 = generatePkcs8PrivateKey())

    private fun connectorReturning(transactionsJson: String): EnableBankingConnector {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(method(org.springframework.http.HttpMethod.GET))
            .andRespond(withSuccess("""{"transactions":$transactionsJson}""", MediaType.APPLICATION_JSON))
        return EnableBankingConnector(props, EnableBankingJwt(), builder)
    }

    @Test
    fun `a pending ING entry without credit_debit_indicator is kept, direction from signed amount`() {
        // ING pending shape: no credit_debit_indicator, no booking_date, signed amount, transaction_date only.
        val connector = connectorReturning(
            """[
              {
                "status": "PDNG",
                "transaction_amount": {"amount": "-42.50", "currency": "EUR"},
                "transaction_date": "2026-07-09",
                "value_date": "2026-07-09",
                "creditor": {"name": "Corner Shop"},
                "remittance_information": ["Card payment"]
              }
            ]""",
        )

        val page = connector.fetchMovements(
            creds, "session-1", "acc-1", LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-11"),
            FetchStrategy.DEFAULT, null, null,
        )

        val m = page.movements.single()
        assertThat(m.direction).isEqualTo(Direction.expense)  // derived from the negative amount
        assertThat(m.amount).isEqualByComparingTo("42.50")    // stored positive
        assertThat(m.bookingDate).isEqualTo(LocalDate.parse("2026-07-09")) // value_date fallback
        assertThat(m.counterparty).isEqualTo("Corner Shop")
    }

    @Test
    fun `a positive pending amount without indicator maps to income`() {
        val connector = connectorReturning(
            """[
              {
                "status": "PDNG",
                "transaction_amount": {"amount": "1000.00", "currency": "EUR"},
                "transaction_date": "2026-07-09"
              }
            ]""",
        )

        val m = connector.fetchMovements(
            creds, "s", "a", null, null, FetchStrategy.DEFAULT, null, null,
        ).movements.single()
        assertThat(m.direction).isEqualTo(Direction.income)
        assertThat(m.amount).isEqualByComparingTo("1000.00")
        assertThat(m.bookingDate).isEqualTo(LocalDate.parse("2026-07-09")) // transaction_date fallback
    }

    @Test
    fun `a booked entry still maps by its credit_debit_indicator`() {
        val connector = connectorReturning(
            """[
              {
                "status": "BOOK",
                "credit_debit_indicator": "DBIT",
                "transaction_amount": {"amount": "12.50", "currency": "EUR"},
                "booking_date": "2026-07-08",
                "creditor": {"name": "Grocery"}
              }
            ]""",
        )

        val m = connector.fetchMovements(
            creds, "s", "a", null, null, FetchStrategy.DEFAULT, null, null,
        ).movements.single()
        assertThat(m.direction).isEqualTo(Direction.expense)
        assertThat(m.bookingDate).isEqualTo(LocalDate.parse("2026-07-08"))
    }

    @Test
    fun `an entry with no amount and no date is still dropped`() {
        val connector = connectorReturning(
            """[
              {"status": "PDNG", "credit_debit_indicator": "DBIT"},
              {
                "credit_debit_indicator": "CRDT",
                "transaction_amount": {"amount": "5.00", "currency": "EUR"},
                "booking_date": "2026-07-08"
              }
            ]""",
        )

        // First row (no amount, no date) drops; the valid second row survives.
        val page = connector.fetchMovements(creds, "s", "a", null, null, FetchStrategy.DEFAULT, null, null)
        assertThat(page.movements).hasSize(1)
        assertThat(page.movements.single().direction).isEqualTo(Direction.income)
    }

    @Test
    fun `an ASPSP error carries the provider code and its message, not the HTTP status`() {
        // Real Bankinter failure body: `code` is the numeric status, the machine code is in `error`.
        val connector = connectorFailing(
            HttpStatus.BAD_REQUEST,
            """{"code":400,"message":"Error interacting with ASPSP","detail":"Unknown error","error":"ASPSP_ERROR"}""",
        )

        val thrown = catchThrowableOfType(
            { connector.fetchMovements(creds, "s", "a", null, null, FetchStrategy.DEFAULT, null, null) },
            BankConnectorException::class.java,
        )

        assertThat(thrown.providerCode).isEqualTo("ASPSP_ERROR")
        assertThat(thrown.message).isEqualTo("ASPSP_ERROR: Error interacting with ASPSP — Unknown error")
    }

    @Test
    fun `a rate limit is recognised from the error field even though code holds the status`() {
        val connector = connectorFailing(
            HttpStatus.TOO_MANY_REQUESTS,
            """{"code":429,"message":"Rate limit exceeded","error":"ASPSP_RATE_LIMIT_EXCEEDED"}""",
        )

        // Must be the dedicated type: the sync service backs off on it instead of failing hard.
        assertThatThrownBy { connector.fetchMovements(creds, "s", "a", null, null, FetchStrategy.DEFAULT, null, null) }
            .isInstanceOf(RateLimitExceededException::class.java)
    }

    private fun connectorFailing(status: HttpStatus, body: String): EnableBankingConnector {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(method(org.springframework.http.HttpMethod.GET))
            .andRespond(withStatus(status).body(body).contentType(MediaType.APPLICATION_JSON))
        return EnableBankingConnector(props, EnableBankingJwt(), builder)
    }

    private fun generatePkcs8PrivateKey(): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Base64.getEncoder().encodeToString(pair.private.encoded)
    }
}
