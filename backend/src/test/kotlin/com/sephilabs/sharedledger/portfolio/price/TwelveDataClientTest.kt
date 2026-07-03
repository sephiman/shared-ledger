package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

class TwelveDataClientTest {

    private val props = AppProperties(
        portfolio = AppProperties.Portfolio(
            twelvedata = AppProperties.PriceProviderConfig(
                baseUrl = "https://api.twelvedata.com",
                apiKey = "td-key",
            ),
        ),
    )

    private fun client(): Pair<TwelveDataClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return TwelveDataClient(props, builder) to server
    }

    @Test
    fun `search maps candidates with exchange and currency`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/symbol_search?symbol=AAPL")))
            .andRespond(
                withSuccess(
                    """
                    {"data":[{"symbol":"AAPL","instrument_name":"Apple Inc",
                      "exchange":"NASDAQ","currency":"USD","country":"United States","instrument_type":"Common Stock"}],
                     "status":"ok"}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val candidates = client.search("AAPL")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].provider).isEqualTo("twelvedata")
        assertThat(candidates[0].providerSymbol).isEqualTo("AAPL")
        assertThat(candidates[0].exchange).isEqualTo("NASDAQ")
        assertThat(candidates[0].currency).isEqualTo("USD")
    }

    @Test
    fun `dailyHistory parses the close per day and sorts ascending`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/time_series")))
            .andRespond(
                withSuccess(
                    """
                    {"meta":{"symbol":"AAPL"},
                     "values":[{"datetime":"2026-05-02","close":"192.50"},{"datetime":"2026-05-01","close":"190.00"}],
                     "status":"ok"}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.dailyHistory("AAPL", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2))

        assertThat(history.prices).hasSize(2)
        assertThat(history.prices[0].date).isEqualTo(LocalDate.of(2026, 5, 1))
        assertThat(history.prices[0].price).isEqualByComparingTo(BigDecimal("190.00"))
        assertThat(history.prices[1].date).isEqualTo(LocalDate.of(2026, 5, 2))
    }

    @Test
    fun `HTTP 200 error bodies are detected and thrown`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/time_series")))
            .andRespond(
                withSuccess(
                    """{"code":429,"message":"You have run out of API credits","status":"error"}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        assertThatThrownBy { client.dailyHistory("AAPL", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2)) }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("API credits")
    }
}
