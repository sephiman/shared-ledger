package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankFxConverter
import com.sephilabs.sharedledger.bank.BankSyncRun
import com.sephilabs.sharedledger.bank.BankSyncRunRepository
import com.sephilabs.sharedledger.bank.CategorizationService
import com.sephilabs.sharedledger.bank.ConnectionStatus
import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.bank.SyncRunStatus
import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.BankConnectorException
import com.sephilabs.sharedledger.bank.connector.BankCrypto
import com.sephilabs.sharedledger.bank.connector.BankMovement
import com.sephilabs.sharedledger.bank.connector.ConsentStatus
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Idempotent per-connection sync. Resumes from the latest booking date already stored (no cursor
 * table), upserts movements by (connection, bankMovementId), applies categorisation rules, and
 * records a [BankSyncRun]. Respects the PSD2 ≤4 calls/consent/day budget and never lets one
 * connection's failure or expiry affect another.
 *
 * Provider HTTP (and the FX lookups it needs) run **outside** any DB transaction; only the short
 * persist steps use one (via [TransactionTemplate]) — so a slow bank never holds a pooled DB
 * connection open. Use [enqueue] to run off the caller's thread.
 */
@Service
class BankSyncService(
    private val props: AppProperties,
    private val connector: BankConnector,
    private val crypto: BankCrypto,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val pending: PendingMovementRepository,
    private val syncRuns: BankSyncRunRepository,
    private val categorization: CategorizationService,
    private val fx: BankFxConverter,
    private val metrics: AppMetrics,
    private val notifications: NotificationPublisher,
    txManager: PlatformTransactionManager,
    @Qualifier("bankSyncExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(BankSyncService::class.java)
    private val tx = TransactionTemplate(txManager)

    /** Fetched-but-not-yet-persisted movement, with its EUR amount already resolved (FX off-tx). */
    private data class Fetched(val accountId: UUID, val movement: BankMovement, val amountBase: BigDecimal)

    /** Run a connection off the caller's thread (used by the manual endpoint and the link listener). */
    fun enqueue(connectionId: UUID) {
        executor.execute {
            try {
                sync(connectionId)
            } catch (ex: Exception) {
                log.error("bank_sync enqueue failed connection={}", connectionId, ex)
            }
        }
    }

    /** Runs one connection synchronously. Safe to call repeatedly; returns new movements ingested. */
    fun sync(connectionId: UUID): Int {
        val connection = connections.findById(connectionId).orElse(null) ?: return 0
        if (!connection.ingestionEnabled) return 0

        val today = LocalDate.now()
        val maxCalls = props.enableBanking.maxCallsPerDay
        var callsUsed = if (connection.callsResetOn != today) 0 else connection.callsUsedToday
        if (maxCalls - callsUsed <= 0) {
            log.info("bank_sync_skipped connection={} reason=call_budget", connectionId)
            return 0
        }
        val sessionId = connection.sessionIdEnc?.let { crypto.decrypt(it) }
        if (sessionId == null) {
            write { markConnection(connectionId) { it.status = ConnectionStatus.error } }
            return 0
        }

        val runId = write { syncRuns.save(BankSyncRun(connectionId = connectionId, startedAt = Instant.now())).id }

        return try {
            // ---- HTTP phase: no DB transaction is held while we talk to the provider ----
            val status = connector.sessionStatus(sessionId); callsUsed++
            if (status != ConsentStatus.ACTIVE) {
                write {
                    markConnection(connectionId) {
                        it.status = ConnectionStatus.expired
                        it.callsUsedToday = callsUsed
                        it.callsResetOn = today
                    }
                    finishRun(runId, SyncRunStatus.success, 0)
                }
                return 0
            }

            val fetched = mutableListOf<Fetched>()
            budget@ for (account in accounts.findAllByConnectionId(connectionId)) {
                val cursor = pending.findMaxBookingDate(connectionId, account.id)
                    ?: today.minusDays(props.enableBanking.backfillDays)
                var continuationKey: String? = null
                do {
                    if (maxCalls - callsUsed <= 0) {
                        log.info("bank_sync_paused connection={} reason=call_budget", connectionId)
                        break@budget
                    }
                    val page = connector.fetchMovements(sessionId, account.accountUid, cursor, continuationKey)
                    callsUsed++
                    // FX conversion may hit the network on a cache miss — keep it out of the persist tx.
                    page.movements.forEach {
                        fetched += Fetched(account.id, it, fx.toBase(it.amount, it.currency, it.bookingDate))
                    }
                    continuationKey = page.continuationKey
                } while (continuationKey != null)
            }

            // ---- Persist phase: one short transaction ----
            val callsAtEnd = callsUsed
            write {
                var n = 0
                for (f in fetched) {
                    val m = f.movement
                    if (pending.existsByConnectionIdAndBankMovementId(connectionId, m.bankMovementId)) continue
                    val entity = PendingMovement(
                        householdId = connection.householdId,
                        connectionId = connectionId,
                        accountId = f.accountId,
                        bankMovementId = m.bankMovementId,
                        bookingDate = m.bookingDate,
                        valueDate = m.valueDate,
                        direction = m.direction,
                        amount = f.amountBase,
                        originalAmount = if (isBase(m.currency)) null else m.amount,
                        originalCurrency = if (isBase(m.currency)) null else m.currency,
                        counterparty = m.counterparty?.take(255),
                        description = m.description?.take(500),
                        reference = m.reference?.take(255),
                        status = MovementStatus.pending,
                    )
                    entity.suggestedCategoryCode = categorization.suggestCategory(connection.householdId, entity)
                    pending.save(entity)
                    n++
                }
                markConnection(connectionId) {
                    it.lastSyncedAt = Instant.now()
                    it.status = ConnectionStatus.active
                    it.callsUsedToday = callsAtEnd
                    it.callsResetOn = today
                }
                finishRun(runId, SyncRunStatus.success, n)
                metrics.bankMovementsIngested(n)
                // Published inside the tx so the AFTER_COMMIT Telegram listener fires only on commit.
                notifications.bankMovementsToReview(connection.householdId, n, NotifyActor.Schedule(connection.householdId))
                n
            }
        } catch (ex: Exception) {
            log.error("bank_sync_failed connection={}", connectionId, ex)
            metrics.bankSyncFailure()
            val callsAtEnd = callsUsed
            write {
                markConnection(connectionId) {
                    it.status = ConnectionStatus.suspended
                    it.callsUsedToday = callsAtEnd
                    it.callsResetOn = today
                }
                finishRun(
                    runId, SyncRunStatus.error, 0,
                    if (ex is BankConnectorException) "BANK_PROVIDER_ERROR" else "BANK_SYNC_ERROR",
                    ex.message?.take(500),
                )
            }
            0
        }
    }

    private fun <T> write(block: () -> T): T = tx.execute { block() }!!

    private fun markConnection(id: UUID, mutate: (BankConnection) -> Unit) {
        connections.findById(id).ifPresent(mutate)
    }

    private fun finishRun(runId: UUID, status: SyncRunStatus, count: Int, code: String? = null, message: String? = null) {
        syncRuns.findById(runId).ifPresent {
            it.status = status
            it.newMovements = count
            it.finishedAt = Instant.now()
            it.errorCode = code
            it.errorMessage = message
        }
    }

    private fun isBase(currency: String): Boolean = currency.equals(props.portfolio.baseCurrency, ignoreCase = true)
}
