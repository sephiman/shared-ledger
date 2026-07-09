package com.sephilabs.sharedledger.bank.sync

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Runs the initial backfill sync after the connection commits. AFTER_COMMIT guarantees the
 * connection + accounts are visible; [BankSyncService.enqueue] moves the provider I/O onto the
 * bank-sync executor (synchronous in tests). Failures are swallowed there and re-attempted by the
 * scheduled sync — one bad link never breaks the flow.
 */
@Component
class BankConnectionLinkedListener(private val syncService: BankSyncService) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onConnectionLinked(event: BankConnectionLinked) {
        // Initial link → full history (longest), run interactively with the holder's PSU context.
        syncService.enqueue(event.connectionId, SyncMode.INITIAL, event.psu)
    }
}
