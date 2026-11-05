package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

enum class ManualRefreshOutcome { STARTED, ALREADY_RUNNING, COOLDOWN }

data class ManualRefreshStatus(
    val running: Boolean,
    val lastFinishedAt: Instant?,
    // Null when a click would start a run right now (or one is already running).
    val nextAllowedAt: Instant?,
    val equitiesStaleOnly: Boolean,
)

data class ManualRefreshTrigger(val outcome: ManualRefreshOutcome, val status: ManualRefreshStatus)

/** A household's "Refresh now": the scheduled gap-fill narrowed to its own series, off the request thread.
 *  One run at a time per household, a cooldown after each, and a shared back-off from a provider that
 *  answered 429. State is in memory: a restart only forgets cooldowns. */
@Service
class ManualPriceRefreshService(
    private val refresh: PriceRefreshService,
    @Qualifier("backfillExecutor") private val executor: Executor,
    private val props: AppProperties,
    private val metrics: AppMetrics,
) {
    private val log = LoggerFactory.getLogger(ManualPriceRefreshService::class.java)

    private data class HouseholdRun(val startedAt: Instant, val finishedAt: Instant?) {
        val running: Boolean get() = finishedAt == null
    }

    private val runs = ConcurrentHashMap<UUID, HouseholdRun>()
    private val backedOffUntil = ConcurrentHashMap<String, Instant>()

    private val limits get() = props.portfolio.manualRefresh
    private val equitiesStaleOnly get() = !props.portfolio.equityProvider.allowsIntradayRefresh

    fun trigger(householdId: UUID, now: Instant = Instant.now()): ManualRefreshTrigger {
        var outcome = ManualRefreshOutcome.STARTED
        runs.compute(householdId) { _, run ->
            when {
                run == null -> HouseholdRun(now, null)
                run.running -> run.also { outcome = ManualRefreshOutcome.ALREADY_RUNNING }
                now.isBefore(nextAllowedAt(run)) -> run.also { outcome = ManualRefreshOutcome.COOLDOWN }
                else -> HouseholdRun(now, null)
            }
        }
        metrics.manualPriceRefresh(outcome.name.lowercase())
        log.info("portfolio_price_refresh_requested household={} outcome={}", householdId, outcome)
        if (outcome == ManualRefreshOutcome.STARTED) start(householdId, now)
        return ManualRefreshTrigger(outcome, status(householdId, now))
    }

    fun status(householdId: UUID, now: Instant = Instant.now()): ManualRefreshStatus {
        val run = runs[householdId]
        val next = run?.takeUnless { it.running }?.let(::nextAllowedAt)?.takeIf { now.isBefore(it) }
        return ManualRefreshStatus(
            running = run?.running == true,
            lastFinishedAt = run?.finishedAt,
            nextAllowedAt = next,
            equitiesStaleOnly = equitiesStaleOnly,
        )
    }

    private fun nextAllowedAt(run: HouseholdRun): Instant =
        (run.finishedAt ?: run.startedAt).plusSeconds(limits.cooldownSeconds)

    private fun start(householdId: UUID, startedAt: Instant) {
        try {
            executor.execute { runAll(householdId, startedAt) }
        } catch (ex: RejectedExecutionException) {
            // Never leave the household stuck "running" behind a run that was never queued.
            runs.remove(householdId)
            throw ex
        }
    }

    private fun runAll(householdId: UUID, startedAt: Instant) {
        val scope = ManualRefreshScope(
            householdId = householdId,
            skipFetchedSince = startedAt.minusSeconds(limits.freshWithinSeconds),
            equitiesStaleOnly = equitiesStaleOnly,
        )
        val today = LocalDate.now()
        try {
            guarded("fx", provider = null, startedAt) { refresh.refreshFx(today, scope) }
            guarded("crypto", CoinGeckoClient.PROVIDER, startedAt) { refresh.refreshCrypto(today, scope) }
            guarded("equity", props.portfolio.equityProvider.asHoldingProvider().name, startedAt) {
                refresh.refreshEquities(today, scope)
            }
        } finally {
            runs.computeIfPresent(householdId) { _, run -> run.copy(finishedAt = Instant.now()) }
        }
    }

    // Measured against the run's own start, like the freshness window, so one run sees one clock.
    private fun guarded(job: String, provider: String?, at: Instant, block: () -> Unit) {
        val until = provider?.let { backedOffUntil[it] }
        if (until != null && at.isBefore(until)) {
            log.info("Manual {} refresh skipped: {} rate-limited us until {}", job, provider, until)
            return
        }
        try {
            block()
        } catch (ex: RateLimitedException) {
            backedOffUntil[ex.provider] = at.plusSeconds(limits.rateLimitBackoffSeconds)
            log.warn("Manual {} refresh stopped: {}; backing off for {}s", job, ex.message, limits.rateLimitBackoffSeconds)
        } catch (ex: Exception) {
            log.error("Manual {} refresh failed", job, ex)
        }
    }
}
