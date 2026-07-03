package com.sephilabs.sharedledger.portfolio.price

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Cron entry points for the portfolio price jobs. Ordering matters at night:
 * FX runs first (00:30) so the equity job (01:00) converts with the day's rate.
 * Crypto refreshes hourly and is FX-independent (priced directly in base currency).
 * Each job swallows its own failures so one bad provider never kills the scheduler.
 */
@Component
class PortfolioPriceScheduler(private val refreshService: PriceRefreshService) {

    private val log = LoggerFactory.getLogger(PortfolioPriceScheduler::class.java)

    @Scheduled(cron = "\${app.portfolio.crypto-refresh-cron}", zone = "\${app.scheduler.timezone}")
    fun cryptoHourly() = guarded("crypto") { refreshService.refreshCrypto(LocalDate.now()) }

    @Scheduled(cron = "\${app.portfolio.fx-refresh-cron}", zone = "\${app.scheduler.timezone}")
    fun fxDaily() = guarded("fx") { refreshService.refreshFx(LocalDate.now()) }

    @Scheduled(cron = "\${app.portfolio.equity-refresh-cron}", zone = "\${app.scheduler.timezone}")
    fun equityNightly() = guarded("equity") { refreshService.refreshEquities(LocalDate.now()) }

    private fun guarded(job: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            log.error("Portfolio {} refresh job failed", job, ex)
        }
    }
}
