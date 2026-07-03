package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

class FrankfurterClientTest {

    private val props = AppProperties(
        portfolio = AppProperties.Portfolio(
            frankfurter = AppProperties.PriceProviderConfig(baseUrl = "https://api.frankfurter.dev"),
        ),
    )

    @Test
    fun `history parses business-day rates only`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = FrankfurterClient(props, builder)

        server.expect(requestTo(containsString("/v1/2026-05-01..2026-05-04")))
            .andRespond(
                withSuccess(
                    """
                    {"base":"USD","rates":{
                      "2026-05-01":{"EUR":0.921},
                      "2026-05-04":{"EUR":0.925}
                    }}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val history = client.history("USD", "EUR", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))

        assertThat(history).hasSize(2)
        assertThat(history[0].date).isEqualTo(LocalDate.of(2026, 5, 1))
        assertThat(history[0].price).isEqualByComparingTo(BigDecimal("0.921"))
        assertThat(history[1].date).isEqualTo(LocalDate.of(2026, 5, 4))
    }
}
