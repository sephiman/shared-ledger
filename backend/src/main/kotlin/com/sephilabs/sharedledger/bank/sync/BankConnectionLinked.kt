package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.connector.PsuContext
import java.util.UUID

/**
 * Published after a connection is linked/re-linked and its row has committed. Consumed
 * AFTER_COMMIT on the bank-sync executor to run the initial backfill sync off the request thread.
 * [psu] carries the linking holder's IP/User-Agent so the initial full-history sync runs as an
 * interactive (online) fetch, exempt from the background rate limit.
 */
data class BankConnectionLinked(val connectionId: UUID, val psu: PsuContext?)
