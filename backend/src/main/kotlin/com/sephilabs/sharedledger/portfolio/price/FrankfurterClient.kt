package com.sephilabs.sharedledger.portfolio.price

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Frankfurter adapter for ECB reference FX rates. Keyless. Returns business-day
 * observations only; weekends/holidays are forward-filled at read time, never stored.
 */
@Component
class FrankfurterClient(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : FxRateProvider {

    private val rest: RestClient = restClientBuilder
        .baseUrl(props.portfolio.frankfurter.baseUrl)
        .build()

    // Nullable: Jackson maps absent keys to null rather than Kotlin defaults.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RangeResponse(val rates: Map<String, Map<String, BigDecimal>>? = null)

    override fun history(base: String, quote: String, from: LocalDate, to: LocalDate): List<DailyPrice> =
        try {
            val response = rest.get()
                .uri { builder ->
                    builder.path("/v1/{range}")
                        .queryParam("base", base)
                        .queryParam("symbols", quote)
                        .build("$from..$to")
                }
                .retrieve()
                .body(RangeResponse::class.java)
            response?.rates.orEmpty()
                .mapNotNull { (date, quotes) ->
                    quotes[quote]?.let { DailyPrice(LocalDate.parse(date), it) }
                }
                .sortedBy { it.date }
        } catch (ex: Exception) {
            throw ProviderException("Frankfurter history failed: ${ex.message}", ex)
        }

    companion object {
        const val PROVIDER = "frankfurter"
    }
}
