package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.portfolio.HoldingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor

/** Requests a price backfill for a holding just created, linked, or given an earlier lot. [newEarliest] is
 *  set when an older lot appeared (extend the head to it); null means a full (re)backfill. */
data class HoldingBackfillRequested(
    val holdingId: UUID,
    val newEarliest: LocalDate? = null,
)

/** Runs the backfill off the request thread and only after the triggering mutation commits: AFTER_COMMIT
 *  guarantees the holding/lot is visible. Failure is non-fatal — provider errors are swallowed and the
 *  nightly gap-fill re-attempts any range still missing. */
@Component
class HoldingBackfillListener(
    private val holdings: HoldingRepository,
    private val priceRefresh: PriceRefreshService,
    @Qualifier("backfillExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(HoldingBackfillListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onBackfillRequested(event: HoldingBackfillRequested) {
        executor.execute { runBackfill(event) }
    }

    private fun runBackfill(event: HoldingBackfillRequested) {
        try {
            // Reloaded in the backfill thread's own transaction; the committing transaction is gone.
            val holding = holdings.findById(event.holdingId).orElse(null)
            if (holding == null) {
                log.warn("Backfill skipped: holding {} no longer exists", event.holdingId)
                return
            }
            val newEarliest = event.newEarliest
            if (newEarliest != null) {
                priceRefresh.extendBackfill(holding, newEarliest)
            } else {
                priceRefresh.backfillForHolding(holding)
            }
        } catch (ex: Exception) {
            log.error("Backfill for holding {} failed", event.holdingId, ex)
        }
    }
}
