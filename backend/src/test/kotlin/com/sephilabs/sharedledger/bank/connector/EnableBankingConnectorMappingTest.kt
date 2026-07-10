package com.sephilabs.sharedledger.bank.connector

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.KeyPairGenerator
import java.time.LocalDate
import java.util.Base64

/**
 * Focused coverage for [EnableBankingConnector.mapMovement] — the JSON → [BankMovement] mapping that
 * [com.sephilabs.sharedledger.bank.FakeBankConnector] bypasses. Drives the real connector over a
 * mocked HTTP layer so ASPSP payload quirks (here: ING pending entries) are exercised end-to-end.
 */
class EnableBankingConnectorMappingTest {

    // A real (throwaway) RSA key so EnableBankingJwt.bearer() signs without hitting config.
    private val props = AppProperties(
        enableBanking = AppProperties.EnableBanking(
            baseUrl = "https://api.enablebanking.test",
            appId = "test-app",
            privateKey = generatePkcs8PrivateKey(),
            minRequestIntervalMs = 0,
        ),
    )

    private fun connectorReturning(transactionsJson: String): EnableBankingConnector {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(method(org.springframework.http.HttpMethod.GET))
            .andRespond(withSuccess("""{"transactions":$transactionsJson}""", MediaType.APPLICATION_JSON))
        return EnableBankingConnector(props, EnableBankingJwt(props), builder)
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
            "session-1", "acc-1", LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-11"),
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
            "s", "a", null, null, FetchStrategy.DEFAULT, null, null,
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
            "s", "a", null, null, FetchStrategy.DEFAULT, null, null,
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
        val page = connector.fetchMovements("s", "a", null, null, FetchStrategy.DEFAULT, null, null)
        assertThat(page.movements).hasSize(1)
        assertThat(page.movements.single().direction).isEqualTo(Direction.income)
    }

    private fun generatePkcs8PrivateKey(): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Base64.getEncoder().encodeToString(pair.private.encoded)
    }
}
