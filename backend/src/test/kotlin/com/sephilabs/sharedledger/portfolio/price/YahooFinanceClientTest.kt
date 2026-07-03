package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class YahooFinanceClientTest {

    private val props = AppProperties(portfolio = AppProperties.Portfolio())

    private fun client(): Triple<YahooFinanceClient, MockRestServiceServer, MockRestServiceServer> {
        val primaryBuilder = RestClient.builder()
        val fallbackBuilder = RestClient.builder()
        val primary = MockRestServiceServer.bindTo(primaryBuilder).build()
        val fallback = MockRestServiceServer.bindTo(fallbackBuilder).build()
        return Triple(YahooFinanceClient(props, primaryBuilder, fallbackBuilder), primary, fallback)
    }

    @Test
    fun `search resolves ISIN or ticker into TICKER_SUFFIX candidates with a browser user agent`() {
        val (client, primary, _) = client()
        primary.expect(requestTo(containsString("/v1/finance/search?q=IE0003XJA0J9")))
            .andExpect(header(HttpHeaders.USER_AGENT, YahooFinanceClient.USER_AGENT))
            .andRespond(
                withSuccess(
                    """
                    {"quotes":[
                      {"symbol":"WEBN.DE","shortname":"Amundi Prime All Country","longname":"Amundi Prime All Country World UCITS ETF",
                       "exchange":"GER","exchDisp":"XETRA","quoteType":"ETF","currency":"EUR"},
                      {"symbol":"WEBN-NEWS","quoteType":"NEWS"}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val candidates = client.searchByIsin("IE0003XJA0J9")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].provider).isEqualTo("yahoo")
        assertThat(candidates[0].providerSymbol).isEqualTo("WEBN.DE")
        assertThat(candidates[0].symbol).isEqualTo("WEBN")
        assertThat(candidates[0].exchange).isEqualTo("XETRA")
        assertThat(candidates[0].currency).isEqualTo("EUR")
    }

    @Test
    fun `chart parses raw closes with UTC dates and skips null points`() {
        val (client, primary, _) = client()
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 4)
        fun epoch(date: LocalDate, hour: Long) = date.atStartOfDay(ZoneOffset.UTC).plusHours(hour).toEpochSecond()
        primary.expect(requestTo(containsString("/v8/finance/chart/WEBN.DE")))
            .andExpect { request ->
                assertThat(request.uri.query)
                    .contains("period1=${from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()}")
                    .contains("period2=${to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()}")
                    .contains("interval=1d")
            }
            .andRespond(
                withSuccess(
                    """
                    {"chart":{"result":[{
                      "meta":{"currency":"EUR","regularMarketPrice":10.12},
                      "timestamp":[${epoch(from, 7)},${epoch(from.plusDays(3), 7)},${epoch(from.plusDays(2), 7)}],
                      "indicators":{"quote":[{"close":[9.87,10.12,null]}]}
                    }],"error":null}}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.dailyHistory("WEBN.DE", from, to)

        assertThat(history.currency).isEqualTo("EUR")
        assertThat(history.prices).hasSize(2)
        assertThat(history.prices[0].date).isEqualTo(from)
        assertThat(history.prices[0].price).isEqualByComparingTo(BigDecimal("9.87"))
        assertThat(history.prices[1].date).isEqualTo(from.plusDays(3))
        assertThat(history.prices[1].price).isEqualByComparingTo(BigDecimal("10.12"))
    }

    @Test
    fun `long-range backfill is a single ranged call`() {
        val (client, primary, _) = client()
        val from = LocalDate.of(2019, 1, 2)
        val to = LocalDate.of(2026, 6, 30)
        primary.expect(requestTo(containsString("period1=${from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()}")))
            .andRespond(
                withSuccess(
                    """
                    {"chart":{"result":[{
                      "meta":{"currency":"USD"},
                      "timestamp":[${from.atStartOfDay(ZoneOffset.UTC).toEpochSecond()}],
                      "indicators":{"quote":[{"close":[38.72]}]}
                    }],"error":null}}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.dailyHistory("AAPL", from, to)

        assertThat(history.currency).isEqualTo("USD")
        assertThat(history.prices).hasSize(1)
        assertThat(history.prices[0].date).isEqualTo(from)
        primary.verify()
    }

    @Test
    fun `a chart error body surfaces as ProviderException`() {
        val (client, primary, _) = client()
        primary.expect(requestTo(containsString("/v8/finance/chart/BROKEN.DE")))
            .andRespond(
                withSuccess(
                    """{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        assertThatThrownBy { client.dailyHistory("BROKEN.DE", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2)) }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("No data found")
    }

    @Test
    fun `transport failure on query1 falls back to query2`() {
        val (client, primary, fallback) = client()
        primary.expect(requestTo(containsString("/v1/finance/search")))
            .andRespond(withServerError())
        fallback.expect(requestTo(containsString("/v1/finance/search")))
            .andRespond(
                withSuccess(
                    """{"quotes":[{"symbol":"AAPL","shortname":"Apple","quoteType":"EQUITY","currency":"USD"}]}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val candidates = client.search("AAPL")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].providerSymbol).isEqualTo("AAPL")
        primary.verify()
        fallback.verify()
    }

    @Test
    fun `failure on both hosts surfaces as ProviderException`() {
        val (client, primary, fallback) = client()
        primary.expect(requestTo(containsString("/v1/finance/search"))).andRespond(withServerError())
        fallback.expect(requestTo(containsString("/v1/finance/search"))).andRespond(withServerError())

        assertThatThrownBy { client.search("AAPL") }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("both hosts")
    }
}
