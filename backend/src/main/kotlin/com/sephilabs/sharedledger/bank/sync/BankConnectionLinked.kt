package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.connector.PsuContext
import java.util.UUID

/** Published after a connection is linked and its row has committed; consumed AFTER_COMMIT on the
 *  bank-sync executor to run the initial backfill off the request thread. [psu] carries the linking
 *  holder's IP/User-Agent so that full-history sync runs as an interactive fetch, exempt from the budget. */
data class BankConnectionLinked(val connectionId: UUID, val psu: PsuContext?)
