package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.bank.consent.ConsentExpiryNotifier
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** Scheduled bank sync (twice daily, within the ≤4 calls/consent/day budget) plus a daily consent-expiry
 *  notice. Each connection is synced independently and guarded, so one failure never blocks the others. */
@Component
class BankSyncScheduler(
    private val connections: BankConnectionRepository,
    private val syncService: BankSyncService,
    private val consentExpiry: ConsentExpiryNotifier,
) {
    private val log = LoggerFactory.getLogger(BankSyncScheduler::class.java)

    @Scheduled(cron = "\${app.enable-banking.sync-cron}", zone = "\${app.scheduler.timezone}")
    fun syncAll() {
        val now = Instant.now()
        val ingesting = connections.findAllByIngestionEnabledTrue()
        // Credential states are eligible on purpose: the sync service re-checks and skips quietly
        // while they persist, so a connection recovers on its own once an owner fixes them.
        val eligible = ingesting.filter { it.status in SYNCABLE_STATUSES }
        // A connection that hit the bank's rate limit waits out its backoff before we try again.
        val due = eligible.filter { it.syncBackoffUntil?.isAfter(now) != true }
        log.info(
            "bank_sync_run ingestionEnabled={} due={} skippedByStatus={} skippedByBackoff={}",
            ingesting.size, due.size, ingesting.size - eligible.size, eligible.size - due.size,
        )
        for (connection in due) {
            try {
                syncService.sync(connection.id, SyncMode.SCHEDULED, null)
            } catch (ex: Exception) {
                log.error("bank_sync scheduler failed for connection {}", connection.id, ex)
            }
        }
    }

    // Delegated to its own bean so the call crosses the transactional proxy: the after-commit Telegram
    // listener drops events published outside a transaction.
    @Scheduled(cron = "\${app.enable-banking.reminder-cron}", zone = "\${app.scheduler.timezone}")
    fun notifyConsentExpiry() {
        try {
            consentExpiry.notifyDue(Instant.now())
        } catch (ex: Exception) {
            log.error("bank consent-expiry notice failed", ex)
        }
    }

    private companion object {
        val SYNCABLE_STATUSES = setOf(
            ConnectionStatus.active,
            ConnectionStatus.suspended,
            ConnectionStatus.credentials_required,
            ConnectionStatus.credentials_mismatch,
        )
    }
}
