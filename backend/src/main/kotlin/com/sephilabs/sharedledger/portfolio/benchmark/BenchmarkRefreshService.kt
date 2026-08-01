package com.sephilabs.sharedledger.portfolio.benchmark

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.PriceRefreshService
import com.sephilabs.sharedledger.portfolio.price.ProviderException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate

/** Gap-fill/backfill for benchmark_price, mirroring [PriceRefreshService]: rows upserted by (benchmark_key,
 *  date) so re-runs self-heal, each refresh resuming from the last stored date. A failing benchmark never
 *  aborts the others; foreign-currency ones trigger an FX top-up for the read-time EUR conversion. */
@Service
class BenchmarkRefreshService(
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
    private val source: BenchmarkSource,
    private val priceRefresh: PriceRefreshService,
    private val metrics: AppMetrics,
    private val props: AppProperties,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(BenchmarkRefreshService::class.java)

    private val baseCurrency: String get() = props.portfolio.baseCurrency

    /** Gap-fills every enabled benchmark up to [today]; failures are isolated per benchmark. */
    fun refresh(today: LocalDate) {
        for (benchmark in benchmarks.findAllByEnabledTrueOrderBySortOrderAsc()) {
            try {
                gapFill(benchmark, today)
                metrics.benchmarkRefreshed(benchmark.key)
            } catch (ex: ProviderException) {
                metrics.benchmarkRefreshFailure(benchmark.key)
                log.error("Benchmark refresh failed for {}: {}", benchmark.key, ex.message)
            }
            pace(benchmark)
        }
    }

    /** Bootstraps the whole lookback window when nothing is stored, then only extends the head to the lookback
     *  floor and tails to today. The floor advances with `today`, so a filled series never re-pulls history. */
    private fun gapFill(benchmark: Benchmark, today: LocalDate) {
        val desiredFrom = today.minusDays(props.portfolio.benchmarkHistoryLookbackDays)
        ensureFx(benchmark.currency, today, desiredFrom)

        val minStored = prices.findMinPriceDate(benchmark.key)
        val maxStored = prices.findMaxPriceDate(benchmark.key)
        if (minStored == null || maxStored == null) {
            store(benchmark, source.dailyCloses(benchmark, desiredFrom, today))
            return
        }
        if (desiredFrom.isBefore(minStored)) {
            log.info("Benchmark head gap-fill for {} [{}..{}]", benchmark.key, desiredFrom, minStored.minusDays(1))
            store(benchmark, source.dailyCloses(benchmark, desiredFrom, minStored.minusDays(1)))
        }
        if (maxStored.isBefore(today)) {
            store(benchmark, source.dailyCloses(benchmark, maxStored.plusDays(1), today))
        }
    }

    /** Ensures ECB rates for a foreign benchmark currency cover the window; best effort. */
    private fun ensureFx(currency: String, today: LocalDate, earliestNeeded: LocalDate) {
        if (currency == baseCurrency) return
        runCatching { priceRefresh.refreshFxCurrency(currency, today, earliestNeeded) }
            .onFailure { log.warn("FX top-up for benchmark currency {} failed: {}", currency, it.message) }
    }

    private fun store(benchmark: Benchmark, closes: List<DailyPrice>) {
        val now = Instant.now()
        closes.forEach { upsert(benchmark.key, it, now) }
    }

    private fun upsert(key: String, day: DailyPrice, asOf: Instant) {
        runInTx {
            val existing = prices.findByBenchmarkKeyAndPriceDate(key, day.date)
            if (existing != null) {
                existing.close = day.price
                existing.asOf = asOf
                existing.fetchedAt = Instant.now()
                prices.save(existing)
            } else {
                try {
                    prices.save(
                        BenchmarkPrice(
                            benchmarkKey = key,
                            priceDate = day.date,
                            close = day.price,
                            asOf = asOf,
                        )
                    )
                } catch (ignored: DataIntegrityViolationException) {
                    // Race with a concurrent fill: the row exists now, nothing to do.
                }
            }
        }
    }

    private fun runInTx(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }

    private fun pace(benchmark: Benchmark) {
        val interval = when (benchmark.kind) {
            BenchmarkKind.equity -> props.portfolio.yahoo.minRequestIntervalMs
            BenchmarkKind.crypto -> props.portfolio.binance.minRequestIntervalMs
        }
        if (interval > 0) {
            try {
                Thread.sleep(interval)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
