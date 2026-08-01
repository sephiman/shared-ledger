package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.BankCredentialsService
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Scheduled bank sync (twice daily, within the ≤4 calls/consent/day budget) plus a daily re-link
 *  reminder. Each connection is synced independently and guarded, so one failure never blocks the others. */
@Component
class BankSyncScheduler(
    private val props: AppProperties,
    private val connections: BankConnectionRepository,
    private val credentials: BankCredentialsService,
    private val syncService: BankSyncService,
    private val notifications: NotificationPublisher,
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

    /** Daily re-link reminders for consents nearing expiry (per connection, staggered). */
    @Scheduled(cron = "\${app.enable-banking.reminder-cron}", zone = "\${app.scheduler.timezone}")
    fun remindExpiring() {
        val threshold = LocalDate.now().plusDays(props.enableBanking.reminderDaysBefore)
        connections.findAllByStatus(ConnectionStatus.active).forEach { connection ->
            val expiresOn = connection.consentExpiresAt?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: return@forEach
            // "Re-link soon" is unactionable for a household that can't link at all right now.
            if (!expiresOn.isAfter(threshold) && credentials.resolve(connection.householdId) != null) {
                remind(connection, expiresOn)
            }
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

    private fun remind(connection: BankConnection, expiresOn: LocalDate) {
        try {
            notifications.bankConnectionExpiring(
                householdId = connection.householdId,
                bankName = connection.aspspName,
                label = connection.label,
                expiresOn = expiresOn,
                actor = NotifyActor.Schedule(connection.householdId),
            )
        } catch (ex: Exception) {
            log.error("bank re-link reminder failed for connection {}", connection.id, ex)
        }
    }
}
