package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Binance public market-data adapter (keyless) — crypto history FALLBACK only.
 * Serves daily USDT closes (treated as USD) beyond CoinGecko's 365-day Demo ceiling
 * or when CoinGecko is down. Pair symbols (BTCUSDT) are built on the fly from the
 * holding's ticker and never persisted, so they cannot collide with the CoinGecko
 * ids used as provider_symbol. Unknown pairs return empty instead of failing —
 * not every CoinGecko coin trades on Binance.
 */
@Component
class BinanceClient(
    props: AppProperties,
    restClientBuilder: RestClient.Builder,
) : CryptoHistoryFallback {

    private val rest: RestClient = restClientBuilder
        .baseUrl(props.portfolio.binance.baseUrl)
        .build()

    override fun dailyHistoryUsd(pair: String, from: LocalDate, to: LocalDate): List<DailyPrice> {
        val prices = mutableListOf<DailyPrice>()
        var cursor = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endMs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        try {
            // Klines are capped at 1000 rows per call; page forward until the range is covered.
            while (cursor <= endMs) {
                val klines = rest.get()
                    .uri { builder ->
                        builder.path("/api/v3/klines")
                            .queryParam("symbol", pair)
                            .queryParam("interval", "1d")
                            .queryParam("startTime", cursor)
                            .queryParam("endTime", endMs)
                            .queryParam("limit", PAGE_SIZE)
                            .build()
                    }
                    .retrieve()
                    .body(object : ParameterizedTypeReference<List<List<Any>>>() {})
                    .orEmpty()
                if (klines.isEmpty()) break
                for (kline in klines) {
                    // Kline layout: [0]=openTime ms, [4]=close (string).
                    if (kline.size <= CLOSE_INDEX) continue
                    val openTime = (kline[0] as? Number)?.toLong() ?: continue
                    val close = kline[CLOSE_INDEX]?.toString()?.toBigDecimalOrNull() ?: continue
                    prices += DailyPrice(
                        Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC).toLocalDate(),
                        close,
                    )
                }
                if (klines.size < PAGE_SIZE) break
                val lastOpen = (klines.last()[0] as? Number)?.toLong() ?: break
                cursor = lastOpen + DAY_MS
            }
        } catch (ex: RestClientResponseException) {
            // Invalid/unlisted pair: the coin simply isn't on Binance — no fallback data.
            if (ex.statusCode.is4xxClientError && ex.responseBodyAsString.contains("-1121")) {
                return emptyList()
            }
            throw ProviderException("Binance klines failed for $pair: ${ex.message}", ex)
        } catch (ex: ProviderException) {
            throw ex
        } catch (ex: Exception) {
            throw ProviderException("Binance klines failed for $pair: ${ex.message}", ex)
        }
        return prices.sortedBy { it.date }
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(this) }.getOrNull()

    companion object {
        const val PROVIDER = "binance"
        const val PAGE_SIZE = 1000
        const val CLOSE_INDEX = 4
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
