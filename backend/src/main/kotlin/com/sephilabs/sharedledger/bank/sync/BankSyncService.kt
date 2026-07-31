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
import com.sephilabs.sharedledger.bank.connector.FetchStrategy
import com.sephilabs.sharedledger.bank.connector.PsuContext
import com.sephilabs.sharedledger.bank.connector.RateLimitExceededException
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor

/**
 * How a sync was triggered — this decides the fetch strategy and rate-limit handling:
 * - [INITIAL]: first sync after linking. Full history ([FetchStrategy.LONGEST]), interactive
 *   (PSU headers), so it is not gated by the background per-day budget — page through everything.
 * - [MANUAL]: user pressed "Sync now" while online. Interactive; incremental unless nothing has
 *   ever synced (then full, to self-heal a missed initial sync).
 * - [SCHEDULED]: unattended background sync. Incremental ([FetchStrategy.DEFAULT] from the last
 *   sync point minus an overlap), gated by the ≤4 calls/consent/day budget, backs off on rate limit.
 */
enum class SyncMode { INITIAL, MANUAL, SCHEDULED }

/**
 * Idempotent per-connection sync. The incremental window resumes from the latest booking date
 * already stored (minus a small overlap so late-booked items aren't missed); movements are upserted
 * by (connection, bankMovementId) so re-reads never duplicate. Records a [BankSyncRun] and never
 * lets one connection's failure or expiry affect another.
 *
 * Pagination follows the provider: keep calling with the returned `continuation_key` until a page
 * returns none — an empty page may still carry one. Provider HTTP (and the FX lookups it needs) run
 * **outside** any DB transaction; only the short persist steps use one (via [TransactionTemplate]),
 * so a slow bank never holds a pooled DB connection open. Use [enqueue] to run off the caller's thread.
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
    fun enqueue(connectionId: UUID, mode: SyncMode, psu: PsuContext? = null) {
        executor.execute {
            try {
                sync(connectionId, mode, psu)
            } catch (ex: Exception) {
                log.error("bank_sync enqueue failed connection={}", connectionId, ex)
            }
        }
    }

    /** Runs one connection synchronously. Safe to call repeatedly; returns new movements ingested. */
    fun sync(connectionId: UUID, mode: SyncMode = SyncMode.SCHEDULED, psu: PsuContext? = null): Int {
        val connection = connections.findById(connectionId).orElse(null) ?: return 0
        if (!connection.ingestionEnabled) return 0

        val today = LocalDate.now()
        val background = mode == SyncMode.SCHEDULED
        val maxCalls = props.enableBanking.maxCallsPerDay
        var callsUsed = if (connection.callsResetOn != today) 0 else connection.callsUsedToday
        log.info(
            "bank_sync_start connection={} household={} aspsp='{}' mode={} status={} callsUsed={}/{} lastSyncedAt={}",
            connectionId, connection.householdId, connection.aspspName, mode, connection.status,
            callsUsed, maxCalls, connection.lastSyncedAt,
        )
        // Background gates: honour an active rate-limit backoff and the per-day budget. Interactive
        // syncs (link/manual, PSU present) are exempt — they page through to the end.
        if (background) {
            connection.syncBackoffUntil?.let { until ->
                if (Instant.now().isBefore(until)) {
                    log.info("bank_sync_skipped connection={} reason=rate_limit_backoff until={}", connectionId, until)
                    return 0
                }
            }
            if (maxCalls - callsUsed <= 0) {
                log.info("bank_sync_skipped connection={} reason=call_budget", connectionId)
                return 0
            }
        }
        val sessionId = connection.sessionIdEnc?.let { crypto.decrypt(it) }
        if (sessionId == null) {
            log.warn("bank_sync_skipped connection={} reason=no_session_id (marking error)", connectionId)
            write { markConnection(connectionId) { it.status = ConnectionStatus.error } }
            return 0
        }

        val runId = write { syncRuns.save(BankSyncRun(connectionId = connectionId, startedAt = Instant.now())).id }

        // Full history on the first sync ever (or an explicit initial link); incremental afterwards.
        val fullHistory = mode == SyncMode.INITIAL || connection.lastSyncedAt == null
        val strategy = if (fullHistory) FetchStrategy.LONGEST else FetchStrategy.DEFAULT
        val fetched = mutableListOf<Fetched>()

        return try {
            // ---- HTTP phase: no DB transaction is held while we talk to the provider ----
            val status = connector.sessionStatus(sessionId)
            if (background) callsUsed++
            log.info("bank_sync_session connection={} consentStatus={}", connectionId, status)
            if (status != ConsentStatus.ACTIVE) {
                log.info("bank_sync_stop connection={} reason=consent_not_active status={} (marking expired)", connectionId, status)
                val callsAtEnd = callsUsed
                write {
                    markConnection(connectionId) {
                        it.status = ConnectionStatus.expired
                        if (background) { it.callsUsedToday = callsAtEnd; it.callsResetOn = today }
                    }
                    finishRun(runId, SyncRunStatus.success, 0)
                }
                return 0
            }

            val accountList = accounts.findAllByConnectionId(connectionId)
            log.info("bank_sync_accounts connection={} accounts={} strategy={}", connectionId, accountList.size, strategy)
            budget@ for (account in accountList) {
                val (dateFrom, dateTo) = fetchWindow(strategy, connectionId, account.id, today)
                log.info("bank_sync_account connection={} account={} dateFrom={} dateTo={}", connectionId, account.id, dateFrom, dateTo)
                var continuationKey: String? = null
                var pages = 0
                var fetchedForAccount = 0
                do {
                    if (background && maxCalls - callsUsed <= 0) {
                        log.info("bank_sync_paused connection={} reason=call_budget", connectionId)
                        break@budget
                    }
                    // Stop condition is ONLY "no continuation_key" — an empty/short page may still
                    // carry one, so we keep paging until it's absent.
                    val page = connector.fetchMovements(sessionId, account.accountUid, dateFrom, dateTo, strategy, continuationKey, psu)
                    if (background) callsUsed++
                    pages++
                    fetchedForAccount += page.movements.size
                    log.info(
                        "bank_sync_page connection={} account={} page={} movements={} hasMore={}",
                        connectionId, account.id, pages, page.movements.size, page.continuationKey != null,
                    )
                    // FX conversion may hit the network on a cache miss — keep it out of the persist tx.
                    page.movements.forEach {
                        fetched += Fetched(account.id, it, fx.toBase(it.amount, it.currency, it.bookingDate))
                    }
                    continuationKey = page.continuationKey
                } while (continuationKey != null)
                log.info(
                    "bank_sync_account_done connection={} account={} pages={} fetched={}",
                    connectionId, account.id, pages, fetchedForAccount,
                )
            }
            log.info("bank_sync_fetched connection={} totalFetched={}", connectionId, fetched.size)

            // ---- Persist phase: one short transaction ----
            val callsAtEnd = callsUsed
            write {
                val n = saveNew(connection, fetched)
                markConnection(connectionId) {
                    it.lastSyncedAt = Instant.now()
                    it.status = ConnectionStatus.active
                    it.syncBackoffUntil = null
                    if (background) { it.callsUsedToday = callsAtEnd; it.callsResetOn = today }
                }
                finishRun(runId, SyncRunStatus.success, n)
                metrics.bankMovementsIngested(n)
                log.info("bank_sync_done connection={} fetched={} new={} callsUsed={}/{}", connectionId, fetched.size, n, callsAtEnd, maxCalls)
                // Published inside the tx so the AFTER_COMMIT Telegram listener fires only on commit.
                notifications.bankMovementsToReview(connection.householdId, n, connection.aspspName, connection.label, NotifyActor.Schedule(connection.householdId))
                n
            }
        } catch (ex: RateLimitExceededException) {
            // The bank refused a background fetch: persist whatever we already have (idempotent) and
            // back off. Not a hard failure — the connection stays active and retries after the wait.
            val backoffHours = props.enableBanking.rateLimitBackoffHours
            log.warn("bank_sync_rate_limited connection={} fetched={} backoffHours={}", connectionId, fetched.size, backoffHours)
            metrics.bankSyncFailure()
            val callsAtEnd = callsUsed
            write {
                val n = saveNew(connection, fetched)
                markConnection(connectionId) {
                    it.status = ConnectionStatus.active
                    if (n > 0) it.lastSyncedAt = Instant.now()
                    it.syncBackoffUntil = Instant.now().plus(Duration.ofHours(backoffHours))
                    if (background) { it.callsUsedToday = callsAtEnd; it.callsResetOn = today }
                }
                finishRun(runId, SyncRunStatus.error, n, "ASPSP_RATE_LIMIT_EXCEEDED", "Rate limited; backing off ${backoffHours}h")
                metrics.bankMovementsIngested(n)
                if (n > 0) notifications.bankMovementsToReview(connection.householdId, n, connection.aspspName, connection.label, NotifyActor.Schedule(connection.householdId))
                n
            }
        } catch (ex: Exception) {
            val providerCode = (ex as? BankConnectorException)?.providerCode
            log.error(
                "bank_sync_failed connection={} fetched={} providerCode={}",
                connectionId, fetched.size, providerCode, ex,
            )
            metrics.bankSyncFailure()
            val callsAtEnd = callsUsed
            write {
                // Keep the pages the bank already delivered before it broke — persisting is idempotent
                // (dedup by bankMovementId), and an ASPSP that dies mid-pagination would otherwise
                // never yield a single movement however often we retry. lastSyncedAt is deliberately
                // left untouched: the run failed, so a full-history retry must stay possible.
                val n = saveNew(connection, fetched)
                markConnection(connectionId) {
                    it.status = ConnectionStatus.suspended
                    if (background) { it.callsUsedToday = callsAtEnd; it.callsResetOn = today }
                }
                finishRun(
                    runId, SyncRunStatus.error, n,
                    providerCode ?: if (ex is BankConnectorException) "BANK_PROVIDER_ERROR" else "BANK_SYNC_ERROR",
                    ex.message?.take(500),
                )
                metrics.bankMovementsIngested(n)
                if (n > 0) {
                    notifications.bankMovementsToReview(
                        connection.householdId, n, connection.aspspName, connection.label,
                        NotifyActor.Schedule(connection.householdId),
                    )
                }
                n
            }
        }
    }

    /**
     * Window for the incremental (DEFAULT) strategy: resume from the account's latest stored booking
     * date minus the overlap buffer, up to today. LONGEST ignores the window (the provider finds the
     * earliest transaction and pulls everything forward).
     *
     * Both bounds are **inclusive** at the provider ("including the date"), so the upper bound is
     * today and never tomorrow: a strict ASPSP (Bankinter) answers a future `date_to` with a bare
     * `ASPSP_ERROR`. For the same reason the lower bound is clamped to the unattended history window
     * ([AppProperties.EnableBanking.backfillDays]) — asking a bank for more than it will ever serve
     * in the background gets the whole page rejected rather than trimmed.
     */
    private fun fetchWindow(
        strategy: FetchStrategy,
        connectionId: UUID,
        accountId: UUID,
        today: LocalDate,
    ): Pair<LocalDate?, LocalDate?> {
        if (strategy == FetchStrategy.LONGEST) return null to null
        val earliest = today.minusDays(props.enableBanking.backfillDays)
        val last = pending.findMaxBookingDate(connectionId, accountId) ?: earliest
        val from = last.minusDays(props.enableBanking.syncOverlapDays)
        return maxOf(from, earliest) to today
    }

    /** Dedup + persist fetched movements by (connection, bankMovementId). Returns the count of new rows. */
    private fun saveNew(connection: BankConnection, fetched: List<Fetched>): Int {
        var n = 0
        for (f in fetched) {
            val m = f.movement
            if (pending.existsByConnectionIdAndBankMovementId(connection.id, m.bankMovementId)) continue
            val entity = PendingMovement(
                householdId = connection.householdId,
                connectionId = connection.id,
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
        return n
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
