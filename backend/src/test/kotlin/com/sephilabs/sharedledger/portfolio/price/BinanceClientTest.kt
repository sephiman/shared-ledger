package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class BinanceClientTest {

    private val props = AppProperties(portfolio = AppProperties.Portfolio())

    private fun client(): Pair<BinanceClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return BinanceClient(props, builder) to server
    }

    private fun ms(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `klines parse daily USDT closes with UTC dates`() {
        val (client, server) = client()
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 2)
        server.expect(requestTo(containsString("/api/v3/klines")))
            .andExpect { request ->
                assertThat(request.uri.query)
                    .contains("symbol=BTCUSDT")
                    .contains("interval=1d")
                    .contains("startTime=${ms(from)}")
            }
            .andRespond(
                withSuccess(
                    """
                    [[${ms(from)},"42000.1","43000","41000","42500.55","120",${ms(from.plusDays(1)) - 1},"0",1,"0","0","0"],
                     [${ms(from.plusDays(1))},"42500","44000","42000","43750.00","98",${ms(from.plusDays(2)) - 1},"0",1,"0","0","0"]]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.dailyHistoryUsd("BTCUSDT", from, to)

        assertThat(history).hasSize(2)
        assertThat(history[0].date).isEqualTo(from)
        assertThat(history[0].price).isEqualByComparingTo(BigDecimal("42500.55"))
        assertThat(history[1].date).isEqualTo(from.plusDays(1))
        assertThat(history[1].price).isEqualByComparingTo(BigDecimal("43750.00"))
    }

    @Test
    fun `an unlisted pair returns empty instead of failing`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("symbol=NOPEUSDT")))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"code":-1121,"msg":"Invalid symbol."}"""),
            )

        val history = client.dailyHistoryUsd("NOPEUSDT", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))

        assertThat(history).isEmpty()
    }

    @Test
    fun `other transport errors surface as ProviderException`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/api/v3/klines")))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        org.assertj.core.api.Assertions.assertThatThrownBy {
            client.dailyHistoryUsd("BTCUSDT", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
        }.isInstanceOf(ProviderException::class.java)
    }
}
