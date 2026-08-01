package com.sephilabs.sharedledger.portfolio.benchmark

import com.sephilabs.sharedledger.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.Executor

/** On first start (or after adding a benchmark), populates benchmark_price so the overlay has history
 *  without waiting for the nightly cron. Runs only when an enabled benchmark has no stored data, off the
 *  startup thread, so a normal restart makes no provider calls. */
@Component
class BenchmarkBackfillRunner(
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
    private val refresh: BenchmarkRefreshService,
    private val props: AppProperties,
    @Qualifier("backfillExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(BenchmarkBackfillRunner::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.portfolio.benchmarkBackfillOnStart) return
        val needsBootstrap = benchmarks.findAllByEnabledTrueOrderBySortOrderAsc()
            .any { prices.findMaxPriceDate(it.key) == null }
        if (!needsBootstrap) return
        log.info("Bootstrapping benchmark price history")
        executor.execute {
            try {
                refresh.refresh(LocalDate.now())
            } catch (ex: Exception) {
                log.error("Benchmark bootstrap backfill failed", ex)
            }
        }
    }
}
