package com.sephilabs.sharedledger.portfolio.price

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * EODHD adapter — an official, config-selectable equity alternative
 * (app.portfolio.equity-provider: eodhd). Its free tier covers European UCITS ETFs
 * (Xetra, EUR) but allows only 20 API calls/day and 1 year of history — set
 * equity-history-ceiling-days: 365 when selecting it. Symbols use EODHD's
 * CODE.EXCHANGE convention (WEBN.XETRA, AAPL.US) as the provider_symbol.
 */
class EodhdClient(
    private val props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : EquityPriceProvider {

    private val rest: RestClient = restClientBuilder
        .baseUrl(props.portfolio.eodhd.baseUrl)
        .build()

    // All fields nullable: Jackson maps absent keys to null rather than Kotlin defaults.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchEntry(
        @JsonProperty("Code") val code: String? = null,
        @JsonProperty("Exchange") val exchange: String? = null,
        @JsonProperty("Name") val name: String? = null,
        @JsonProperty("Currency") val currency: String? = null,
        @JsonProperty("ISIN") val isin: String? = null,
        @JsonProperty("Type") val type: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EodEntry(
        val date: String? = null,
        val close: BigDecimal? = null,
    )

    override fun search(query: String): List<SymbolCandidate> = call("search") {
        val entries = rest.get()
            .uri { builder ->
                builder.path("/api/search/{query}")
                    .queryParam("api_token", props.portfolio.eodhd.apiKey)
                    .queryParam("fmt", "json")
                    .queryParam("limit", SEARCH_LIMIT)
                    .build(query)
            }
            .retrieve()
            .body(Array<SearchEntry>::class.java)
        (entries ?: emptyArray()).mapNotNull { entry ->
            val code = entry.code ?: return@mapNotNull null
            val exchange = entry.exchange ?: return@mapNotNull null
            SymbolCandidate(
                provider = PROVIDER,
                providerSymbol = "$code.$exchange",
                name = entry.name ?: code,
                symbol = code,
                currency = entry.currency,
                exchange = exchange,
                isin = entry.isin,
            )
        }
    }

    // EODHD's search endpoint matches ISINs directly.
    override fun searchByIsin(isin: String): List<SymbolCandidate> = search(isin)

    override fun dailyHistory(symbol: String, from: LocalDate, to: LocalDate): EquityHistory =
        call("eod") {
            val entries = rest.get()
                .uri { builder ->
                    builder.path("/api/eod/{symbol}")
                        .queryParam("api_token", props.portfolio.eodhd.apiKey)
                        .queryParam("fmt", "json")
                        .queryParam("period", "d")
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .build(symbol)
                }
                .retrieve()
                .body(Array<EodEntry>::class.java)
            val prices = (entries ?: emptyArray())
                .mapNotNull { entry ->
                    val close = entry.close ?: return@mapNotNull null
                    val date = runCatching { LocalDate.parse(entry.date.orEmpty().take(10)) }.getOrNull()
                        ?: return@mapNotNull null
                    DailyPrice(date, close)
                }
                .sortedBy { it.date }
            // The /eod endpoint does not report a currency.
            EquityHistory(null, prices)
        }

    private fun <T> call(operation: String, block: () -> T): T =
        try {
            block()
        } catch (ex: ProviderException) {
            throw ex
        } catch (ex: Exception) {
            throw ProviderException("EODHD $operation failed: ${ex.message}", ex)
        }

    companion object {
        const val PROVIDER = "eodhd"
        const val SEARCH_LIMIT = 20
    }
}
