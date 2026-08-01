package com.sephilabs.sharedledger.portfolio.benchmark

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.price.CryptoHistoryFallback
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.YahooFinanceClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

/** Fetches a benchmark's daily closes, in the benchmark's own [Benchmark.currency]. */
interface BenchmarkSource {
    fun dailyCloses(benchmark: Benchmark, from: LocalDate, to: LocalDate): List<DailyPrice>
}

/** Default benchmark source: always keyless and deliberately independent of the household's equity
 *  provider, so a benchmark stays comparable however holdings are priced. Equity/commodity come from a
 *  dedicated Yahoo client (`^GSPC`, `GC=F`, `URTH`); crypto from Binance USDT klines. */
@Component
class DefaultBenchmarkSource(
    props: AppProperties,
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    private val cryptoFallback: CryptoHistoryFallback,
) : BenchmarkSource {

    // A private instance, not the wired EquityPriceProvider bean, so benchmark sourcing is
    // never affected by (and never affects) the active equity provider.
    private val yahoo = YahooFinanceClient(props, restClientBuilder.getObject(), restClientBuilder.getObject())

    override fun dailyCloses(benchmark: Benchmark, from: LocalDate, to: LocalDate): List<DailyPrice> =
        when (benchmark.kind) {
            BenchmarkKind.equity -> yahoo.dailyHistory(benchmark.sourceSymbol, from, to).prices
            BenchmarkKind.crypto -> cryptoFallback.dailyHistoryUsd(benchmark.sourceSymbol, from, to)
        }
}
