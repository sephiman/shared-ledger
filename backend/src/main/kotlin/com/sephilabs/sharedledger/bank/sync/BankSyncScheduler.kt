package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Scheduled bank sync (twice daily, within the ≤4 calls/consent/day budget) plus a daily re-link
 * reminder. Each connection is synced independently and guarded, so one failing or expiring
 * connection never blocks the others (staggered expiries → per-connection reminders).
 */
@Component
class BankSyncScheduler(
    private val props: AppProperties,
    private val connections: BankConnectionRepository,
    private val syncService: BankSyncService,
    private val notifications: NotificationPublisher,
) {
    private val log = LoggerFactory.getLogger(BankSyncScheduler::class.java)

    @Scheduled(cron = "\${app.enable-banking.sync-cron}", zone = "\${app.scheduler.timezone}")
    fun syncAll() {
        if (!props.enableBanking.configured) {
            log.info("bank_sync_run skipped=not_configured")
            return
        }
        val ingesting = connections.findAllByIngestionEnabledTrue()
        val due = ingesting
            .filter { it.status == ConnectionStatus.active || it.status == ConnectionStatus.suspended }
        log.info(
            "bank_sync_run ingestionEnabled={} due={} skippedByStatus={}",
            ingesting.size, due.size, ingesting.size - due.size,
        )
        for (connection in due) {
            try {
                syncService.sync(connection.id)
            } catch (ex: Exception) {
                log.error("bank_sync scheduler failed for connection {}", connection.id, ex)
            }
        }
    }

    /** Daily re-link reminders for consents nearing expiry (per connection, staggered). */
    @Scheduled(cron = "\${app.enable-banking.reminder-cron}", zone = "\${app.scheduler.timezone}")
    fun remindExpiring() {
        if (!props.enableBanking.configured) return
        val threshold = LocalDate.now().plusDays(props.enableBanking.reminderDaysBefore)
        connections.findAllByStatus(ConnectionStatus.active).forEach { connection ->
            val expiresOn = connection.consentExpiresAt?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: return@forEach
            if (!expiresOn.isAfter(threshold)) {
                remind(connection, expiresOn)
            }
        }
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
