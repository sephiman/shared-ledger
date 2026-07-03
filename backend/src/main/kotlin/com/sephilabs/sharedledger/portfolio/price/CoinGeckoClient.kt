package com.sephilabs.sharedledger.portfolio.price

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * CoinGecko adapter (Demo plan). Prices are requested directly in the household base
 * currency (vs_currency), so crypto valuations never need an FX conversion.
 */
@Component
class CoinGeckoClient(
    private val props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : CryptoPriceProvider {

    private val rest: RestClient = restClientBuilder
        .baseUrl(props.portfolio.coingecko.baseUrl)
        .defaultHeaders { headers ->
            if (props.portfolio.coingecko.apiKey.isNotBlank()) {
                headers.set(API_KEY_HEADER, props.portfolio.coingecko.apiKey)
            }
        }
        .build()

    // All fields nullable: Jackson maps absent keys to null rather than Kotlin defaults.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponse(val coins: List<Coin>? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Coin(
            val id: String? = null,
            val name: String? = null,
            val symbol: String? = null,
            @JsonProperty("market_cap_rank") val marketCapRank: Int? = null,
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MarketChartResponse(val prices: List<List<BigDecimal>>? = null)

    override fun search(query: String): List<SymbolCandidate> = call("search") {
        val response = rest.get()
            .uri { it.path("/api/v3/search").queryParam("query", query).build() }
            .retrieve()
            .body(SearchResponse::class.java)
        response?.coins.orEmpty().mapNotNull {
            val id = it.id ?: return@mapNotNull null
            SymbolCandidate(
                provider = PROVIDER,
                providerSymbol = id,
                name = it.name ?: id,
                symbol = it.symbol?.uppercase(),
                currency = props.portfolio.baseCurrency,
            )
        }
    }

    override fun currentPrices(ids: List<String>, vsCurrency: String): Map<String, BigDecimal> {
        if (ids.isEmpty()) return emptyMap()
        return call("simple/price") {
            @Suppress("UNCHECKED_CAST")
            val response = rest.get()
                .uri {
                    it.path("/api/v3/simple/price")
                        .queryParam("ids", ids.joinToString(","))
                        .queryParam("vs_currencies", vsCurrency)
                        .build()
                }
                .retrieve()
                .body(Map::class.java) as? Map<String, Map<String, Any>> ?: emptyMap()
            response.mapNotNull { (id, quotes) ->
                quotes[vsCurrency]?.let { id to BigDecimal(it.toString()) }
            }.toMap()
        }
    }

    override fun dailyHistory(id: String, vsCurrency: String, from: LocalDate, to: LocalDate): List<DailyPrice> =
        call("market_chart/range") {
            val response = rest.get()
                .uri {
                    it.path("/api/v3/coins/{id}/market_chart/range")
                        .queryParam("vs_currency", vsCurrency)
                        .queryParam("from", from.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        .queryParam("to", to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        .build(id)
                }
                .retrieve()
                .body(MarketChartResponse::class.java)
            // Ranges under ~90 days come back hourly: keep the last observation per UTC day.
            response?.prices.orEmpty()
                .filter { it.size >= 2 }
                .groupBy { Instant.ofEpochMilli(it[0].toLong()).atZone(ZoneOffset.UTC).toLocalDate() }
                .map { (date, points) -> DailyPrice(date, points.last()[1]) }
                .sortedBy { it.date }
        }

    private fun <T> call(operation: String, block: () -> T): T =
        try {
            block()
        } catch (ex: ProviderException) {
            throw ex
        } catch (ex: Exception) {
            throw ProviderException("CoinGecko $operation failed: ${ex.message}", ex)
        }

    companion object {
        const val PROVIDER = "coingecko"
        const val API_KEY_HEADER = "x-cg-demo-api-key"
    }
}
