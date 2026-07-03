package com.sephilabs.sharedledger.portfolio.price

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Yahoo Finance adapter (UNOFFICIAL) — the default equity provider: keyless, no hard
 * daily quota, long daily history, and native EUR for Xetra listings (`WEBN.DE`).
 * It is a ToS-gray endpoint that can change without notice, so every call sends a
 * realistic User-Agent, transport failures retry once against the query2 fallback
 * host, and all errors surface as [ProviderException] — the refresh layer degrades
 * to last-known-price/stale instead of crashing. Both endpoints used here work
 * without the consent-cookie/crumb handshake; if Yahoo starts requiring it, that
 * handshake (cookie + /v1/test/getcrumb) is the first thing to add.
 * Symbols use Yahoo's TICKER.SUFFIX convention (.DE Xetra, .MI Milan, .L London,
 * .PA Paris, none for US) stored in provider_symbol.
 */
class YahooFinanceClient(
    props: AppProperties,
    primaryBuilder: RestClient.Builder,
    fallbackBuilder: RestClient.Builder,
) : EquityPriceProvider {

    private val primary: RestClient = primaryBuilder
        .baseUrl(props.portfolio.yahoo.baseUrl)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .build()

    private val fallback: RestClient = fallbackBuilder
        .baseUrl(props.portfolio.yahooFallbackBaseUrl)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .build()

    // All fields nullable: Jackson maps absent keys to null rather than Kotlin defaults,
    // and Yahoo omits keys freely.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponse(val quotes: List<Quote>? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Quote(
            val symbol: String? = null,
            val shortname: String? = null,
            val longname: String? = null,
            val exchange: String? = null,
            val exchDisp: String? = null,
            val quoteType: String? = null,
            val currency: String? = null,
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChartResponse(val chart: Chart? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Chart(val result: List<Result>? = null, val error: ChartError? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ChartError(val code: String? = null, val description: String? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Result(
            val meta: Meta? = null,
            val timestamp: List<Long>? = null,
            val indicators: Indicators? = null,
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Meta(val currency: String? = null, val regularMarketPrice: BigDecimal? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Indicators(val quote: List<Quote>? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Quote(val close: List<BigDecimal?>? = null)
    }

    override fun search(query: String): List<SymbolCandidate> = call("search") { rest ->
        val response = rest.get()
            .uri { builder ->
                builder.path("/v1/finance/search")
                    .queryParam("q", query)
                    .queryParam("quotesCount", SEARCH_LIMIT)
                    .queryParam("newsCount", 0)
                    .build()
            }
            .retrieve()
            .body(SearchResponse::class.java)
        response?.quotes.orEmpty()
            .filter { it.quoteType in EQUITY_QUOTE_TYPES }
            .mapNotNull { quote ->
                val providerSymbol = quote.symbol ?: return@mapNotNull null
                SymbolCandidate(
                    provider = PROVIDER,
                    providerSymbol = providerSymbol,
                    name = quote.longname ?: quote.shortname ?: providerSymbol,
                    symbol = providerSymbol.substringBefore('.'),
                    currency = quote.currency,
                    exchange = quote.exchDisp ?: quote.exchange,
                )
            }
    }

    // Yahoo's search endpoint matches ISINs directly.
    override fun searchByIsin(isin: String): List<SymbolCandidate> = search(isin)

    override fun dailyHistory(symbol: String, from: LocalDate, to: LocalDate): EquityHistory =
        call("chart") { rest ->
            val response = rest.get()
                .uri { builder ->
                    builder.path("/v8/finance/chart/{symbol}")
                        .queryParam("period1", from.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        .queryParam("period2", to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        .queryParam("interval", "1d")
                        .build(symbol)
                }
                .retrieve()
                .body(ChartResponse::class.java)
            response?.chart?.error?.let {
                throw ProviderException("Yahoo chart error for $symbol: ${it.code} ${it.description}")
            }
            val result = response?.chart?.result?.firstOrNull() ?: return@call EquityHistory(null, emptyList())
            val timestamps = result.timestamp.orEmpty()
            // Raw close (not adjusted): market value must reflect the actual quote.
            val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
            val prices = timestamps.zip(closes)
                .mapNotNull { (ts, close) ->
                    close ?: return@mapNotNull null
                    DailyPrice(Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate(), close)
                }
                // Intraday runs can return two points for the same UTC day; keep the last.
                .groupBy { it.date }
                .map { (_, points) -> points.last() }
                .sortedBy { it.date }
            // Search results carry no currency; the chart meta is the source of truth.
            EquityHistory(result.meta?.currency?.uppercase(), prices)
        }

    /** Runs [block] against query1, retrying once against query2 on transport failure. */
    private fun <T> call(operation: String, block: (RestClient) -> T): T =
        try {
            block(primary)
        } catch (ex: ProviderException) {
            throw ex
        } catch (primaryFailure: Exception) {
            try {
                block(fallback)
            } catch (ex: ProviderException) {
                throw ex
            } catch (fallbackFailure: Exception) {
                throw ProviderException(
                    "Yahoo $operation failed on both hosts: ${primaryFailure.message}",
                    fallbackFailure,
                )
            }
        }

    companion object {
        const val PROVIDER = "yahoo"
        const val SEARCH_LIMIT = 20

        // Yahoo rejects default HTTP-client agents; a realistic browser UA is mandatory.
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        val EQUITY_QUOTE_TYPES = setOf("EQUITY", "ETF", "MUTUALFUND")
    }
}
