package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executor

/** User-triggered "refresh prices now": the same gap-fill the nightly scheduler runs, off the request
 *  thread and against the shared price_history/fx_rates. A cooldown collapses rapid re-clicks. */
@Service
class ManualPriceRefreshService(
    private val refresh: PriceRefreshService,
    @Qualifier("backfillExecutor") private val executor: Executor,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(ManualPriceRefreshService::class.java)

    private val lock = Any()
    @Volatile
    private var lastRunAt: Instant? = null

    /** Kicks off a full gap-fill off-thread. False when a run already happened within the cooldown, so the
     *  caller can tell the user it was skipped. */
    fun trigger(now: Instant = Instant.now()): Boolean {
        synchronized(lock) {
            val last = lastRunAt
            val cooldown = Duration.ofSeconds(props.portfolio.manualRefreshCooldownSeconds)
            if (last != null && Duration.between(last, now) < cooldown) return false
            lastRunAt = now
        }
        executor.execute { runAll() }
        return true
    }

    private fun runAll() {
        val today = LocalDate.now()
        guarded("fx") { refresh.refreshFx(today) }
        guarded("crypto") { refresh.refreshCrypto(today) }
        guarded("equity") { refresh.refreshEquities(today) }
    }

    private fun guarded(job: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            log.error("Manual {} refresh failed", job, ex)
        }
    }
}
