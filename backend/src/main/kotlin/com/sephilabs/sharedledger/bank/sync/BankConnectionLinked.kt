package com.sephilabs.sharedledger.bank.sync

import java.util.UUID

/**
 * Published after a connection is linked/re-linked and its row has committed. Consumed
 * AFTER_COMMIT on the bank-sync executor to run the initial backfill sync off the request thread.
 */
data class BankConnectionLinked(val connectionId: UUID)
