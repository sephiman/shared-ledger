package com.sephilabs.sharedledger.bank.consent

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.BankCredentialsService
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.notification.ConsentNotice
import com.sephilabs.sharedledger.notification.ConsentState
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Telegram goes out this many days before a consent expires — or on the first run after, if that was missed. */
const val CONSENT_TELEGRAM_NOTICE_DAYS = 1L

/** Announces consents entering the notice window, once per consent cycle, from stored data only — no
 *  provider call, so a paused connection is covered just like a syncing one. */
@Component
class ConsentExpiryNotifier(
    private val props: AppProperties,
    private val connections: BankConnectionRepository,
    private val credentials: BankCredentialsService,
    private val notifications: NotificationPublisher,
) {
    private val log = LoggerFactory.getLogger(ConsentExpiryNotifier::class.java)

    // The marker and the event share this transaction: the listener sends after commit, and a run that
    // rolls back leaves the connection un-notified for the next one.
    @Transactional
    fun notifyDue(now: Instant) {
        val zone = ZoneId.of(props.scheduler.timezone)
        val today = now.atZone(zone).toLocalDate()
        val due = connections.findAllByStatusIn(WATCHED_STATUSES)
            .filter { it.expiryNotifiedAt == null }
            .mapNotNull { connection -> stateOf(connection, now, today, zone)?.let { connection to it } }
        due.groupBy { (connection, _) -> connection.householdId }.forEach { (householdId, entries) ->
            // "Re-link now" is unactionable for a household that can't link at all right now.
            if (credentials.findRow(householdId) == null) return@forEach
            entries.forEach { (connection, _) -> connection.expiryNotifiedAt = now }
            notifications.bankConsentsExpiring(
                householdId,
                entries.map { (connection, state) -> connection.toNotice(state, zone) },
                NotifyActor.Schedule(householdId),
            )
        }
        log.info("bank_consent_expiry_run due={}", due.size)
    }

    private fun stateOf(connection: BankConnection, now: Instant, today: LocalDate, zone: ZoneId): ConsentState? {
        val expiresAt = connection.consentExpiresAt
        // A sync can find the consent gone before its stored date (the bank revoked it early).
        if (connection.status == ConnectionStatus.expired || (expiresAt != null && !expiresAt.isAfter(now))) {
            return ConsentState.EXPIRED
        }
        val expiresOn = expiresAt?.atZone(zone)?.toLocalDate() ?: return null
        return when {
            expiresOn == today -> ConsentState.EXPIRES_TODAY
            !expiresOn.isAfter(today.plusDays(CONSENT_TELEGRAM_NOTICE_DAYS)) -> ConsentState.EXPIRES_TOMORROW
            else -> null
        }
    }

    private fun BankConnection.toNotice(state: ConsentState, zone: ZoneId) =
        ConsentNotice(aspspName, label, consentExpiresAt?.atZone(zone)?.toLocalDate(), state)

    private companion object {
        // The credential states are excluded: re-linking doesn't fix them, an owner's credentials do.
        val WATCHED_STATUSES = setOf(
            ConnectionStatus.active,
            ConnectionStatus.expired,
            ConnectionStatus.suspended,
            ConnectionStatus.error,
        )
    }
}
