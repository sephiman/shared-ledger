package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.hamcrest.Matchers.containsString

class CoinGeckoClientTest {

    private val props = AppProperties(
        portfolio = AppProperties.Portfolio(
            coingecko = AppProperties.PriceProviderConfig(
                baseUrl = "https://api.coingecko.com",
                apiKey = "demo-key",
            ),
        ),
    )

    private fun client(): Pair<CoinGeckoClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return CoinGeckoClient(props, builder) to server
    }

    @Test
    fun `search maps coins and sends the demo api key header`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/api/v3/search?query=bitcoin")))
            .andExpect(header(CoinGeckoClient.API_KEY_HEADER, "demo-key"))
            .andRespond(
                withSuccess(
                    """{"coins":[{"id":"bitcoin","name":"Bitcoin","symbol":"btc","market_cap_rank":1}]}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val candidates = client.search("bitcoin")

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].provider).isEqualTo("coingecko")
        assertThat(candidates[0].providerSymbol).isEqualTo("bitcoin")
        assertThat(candidates[0].symbol).isEqualTo("BTC")
        assertThat(candidates[0].currency).isEqualTo("EUR")
    }

    @Test
    fun `currentPrices batches ids in one call`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("ids=bitcoin,ethereum")))
            .andRespond(
                withSuccess(
                    """{"bitcoin":{"eur":60123.45},"ethereum":{"eur":2456.7}}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val prices = client.currentPrices(listOf("bitcoin", "ethereum"), "eur")

        assertThat(prices["bitcoin"]).isEqualByComparingTo(BigDecimal("60123.45"))
        assertThat(prices["ethereum"]).isEqualByComparingTo(BigDecimal("2456.7"))
    }

    @Test
    fun `dailyHistory downsamples hourly points to the last observation per day`() {
        val (client, server) = client()
        val day1 = LocalDate.of(2026, 5, 1)
        val day2 = LocalDate.of(2026, 5, 2)
        fun ms(date: LocalDate, hour: Long) = date.atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant().toEpochMilli()
        server.expect(requestTo(containsString("/api/v3/coins/bitcoin/market_chart/range")))
            .andRespond(
                withSuccess(
                    """{"prices":[[${ms(day1, 1)},100.0],[${ms(day1, 13)},110.0],[${ms(day2, 2)},120.0],[${ms(day2, 23)},125.5]]}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.dailyHistory("bitcoin", "eur", day1, day2)

        assertThat(history).hasSize(2)
        assertThat(history[0].date).isEqualTo(day1)
        assertThat(history[0].price).isEqualByComparingTo(BigDecimal("110.0"))
        assertThat(history[1].date).isEqualTo(day2)
        assertThat(history[1].price).isEqualByComparingTo(BigDecimal("125.5"))
    }

    @Test
    fun `transport errors surface as ProviderException`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/api/v3/search")))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError())

        org.assertj.core.api.Assertions.assertThatThrownBy { client.search("btc") }
            .isInstanceOf(ProviderException::class.java)
    }
}
