package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

class EodhdClientTest {

    private val props = AppProperties(
        portfolio = AppProperties.Portfolio(
            eodhd = AppProperties.PriceProviderConfig(
                baseUrl = "https://eodhd.com",
                apiKey = "eodhd-key",
            ),
        ),
    )

    private fun client(): Pair<EodhdClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return EodhdClient(props, builder) to server
    }

    @Test
    fun `search builds CODE_EXCHANGE provider symbols and carries currency and ISIN`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/api/search/WEBN")))
            .andExpect { request -> assertThat(request.uri.query).contains("api_token=eodhd-key").contains("fmt=json") }
            .andRespond(
                withSuccess(
                    """
                    [{"Code":"WEBN","Exchange":"XETRA","Name":"Amundi Prime All Country World UCITS ETF",
                      "Type":"ETF","Country":"Germany","Currency":"EUR","ISIN":"IE0003XJA0J9"}]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val candidates = client.search("WEBN")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].provider).isEqualTo("eodhd")
        assertThat(candidates[0].providerSymbol).isEqualTo("WEBN.XETRA")
        assertThat(candidates[0].symbol).isEqualTo("WEBN")
        assertThat(candidates[0].exchange).isEqualTo("XETRA")
        assertThat(candidates[0].currency).isEqualTo("EUR")
        assertThat(candidates[0].isin).isEqualTo("IE0003XJA0J9")
    }

    @Test
    fun `dailyHistory parses daily closes and sorts ascending`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/api/eod/WEBN.XETRA")))
            .andExpect { request ->
                assertThat(request.uri.query)
                    .contains("period=d")
                    .contains("from=2026-05-01")
                    .contains("to=2026-05-02")
            }
            .andRespond(
                withSuccess(
                    """
                    [{"date":"2026-05-02","open":9.9,"high":10.2,"low":9.8,"close":10.12,"adjusted_close":10.12,"volume":10000},
                     {"date":"2026-05-01","open":9.8,"high":10.0,"low":9.7,"close":9.87,"adjusted_close":9.87,"volume":8000}]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.dailyHistory("WEBN.XETRA", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2))

        // The /eod endpoint reports no currency.
        assertThat(history.currency).isNull()
        assertThat(history.prices).hasSize(2)
        assertThat(history.prices[0].date).isEqualTo(LocalDate.of(2026, 5, 1))
        assertThat(history.prices[0].price).isEqualByComparingTo(BigDecimal("9.87"))
        assertThat(history.prices[1].date).isEqualTo(LocalDate.of(2026, 5, 2))
        assertThat(history.prices[1].price).isEqualByComparingTo(BigDecimal("10.12"))
    }

    @Test
    fun `transport errors surface as ProviderException`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/api/eod/WEBN.XETRA")))
            .andRespond(withServerError())

        assertThatThrownBy { client.dailyHistory("WEBN.XETRA", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2)) }
            .isInstanceOf(ProviderException::class.java)
    }
}
