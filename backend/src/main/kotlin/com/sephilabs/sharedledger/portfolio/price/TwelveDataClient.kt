package com.sephilabs.sharedledger.portfolio.price

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/** Twelve Data adapter — config-selectable equity alternative. Not the default: its free plan does not
 *  serve European UCITS ETFs (WEBN returns 404). It reports failures as HTTP 200 bodies with
 *  status="error", so every response type carries status/code/message and is checked before use. */
class TwelveDataClient(
    private val props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : EquityPriceProvider {

    private val rest: RestClient = restClientBuilder
        .baseUrl(props.portfolio.twelvedata.baseUrl)
        .build()

    // All fields nullable: Jackson maps absent keys to null rather than Kotlin defaults.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SymbolSearchResponse(
        val data: List<Entry>? = null,
        val status: String? = null,
        val code: Int? = null,
        val message: String? = null,
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Entry(
            val symbol: String? = null,
            @JsonProperty("instrument_name") val instrumentName: String? = null,
            val exchange: String? = null,
            val currency: String? = null,
            val country: String? = null,
            @JsonProperty("instrument_type") val instrumentType: String? = null,
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TimeSeriesResponse(
        val meta: Meta? = null,
        val values: List<Value>? = null,
        val status: String? = null,
        val code: Int? = null,
        val message: String? = null,
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Meta(val currency: String? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Value(
            val datetime: String? = null,
            val close: BigDecimal? = null,
        )
    }

    override fun search(query: String): List<SymbolCandidate> = call("symbol_search") {
        val response = rest.get()
            .uri { builder ->
                builder.path("/symbol_search")
                    .queryParam("symbol", query)
                    .apply { if (props.portfolio.twelvedata.apiKey.isNotBlank()) queryParam("apikey", props.portfolio.twelvedata.apiKey) }
                    .build()
            }
            .retrieve()
            .body(SymbolSearchResponse::class.java)
        failOnErrorStatus(response?.status, response?.message)
        response?.data.orEmpty().mapNotNull {
            val symbol = it.symbol ?: return@mapNotNull null
            SymbolCandidate(
                provider = PROVIDER,
                providerSymbol = symbol,
                name = it.instrumentName ?: symbol,
                symbol = symbol,
                currency = it.currency,
                exchange = it.exchange,
            )
        }
    }

    override fun searchByIsin(isin: String): List<SymbolCandidate> = search(isin)

    override fun dailyHistory(symbol: String, from: LocalDate, to: LocalDate): EquityHistory =
        call("time_series") {
            val response = rest.get()
                .uri { builder ->
                    builder.path("/time_series")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", "1day")
                        .queryParam("start_date", from.toString())
                        .queryParam("end_date", to.toString())
                        .queryParam("outputsize", MAX_OUTPUT_SIZE)
                        .apply { if (props.portfolio.twelvedata.apiKey.isNotBlank()) queryParam("apikey", props.portfolio.twelvedata.apiKey) }
                        .build()
                }
                .retrieve()
                .body(TimeSeriesResponse::class.java)
            failOnErrorStatus(response?.status, response?.message)
            val prices = response?.values.orEmpty()
                .mapNotNull { value ->
                    val close = value.close ?: return@mapNotNull null
                    // datetime is "YYYY-MM-DD" for daily series.
                    val date = runCatching { LocalDate.parse(value.datetime.orEmpty().take(10)) }.getOrNull()
                        ?: return@mapNotNull null
                    DailyPrice(date, close)
                }
                .sortedBy { it.date }
            EquityHistory(response?.meta?.currency?.uppercase(), prices)
        }

    private fun failOnErrorStatus(status: String?, message: String?) {
        if (status == "error") throw ProviderException("Twelve Data error: ${message ?: "unknown"}")
    }

    private fun <T> call(operation: String, block: () -> T): T =
        try {
            block()
        } catch (ex: ProviderException) {
            throw ex
        } catch (ex: Exception) {
            throw ProviderException("Twelve Data $operation failed: ${ex.message}", ex)
        }

    companion object {
        const val PROVIDER = "twelvedata"
        const val MAX_OUTPUT_SIZE = 5000
    }
}
