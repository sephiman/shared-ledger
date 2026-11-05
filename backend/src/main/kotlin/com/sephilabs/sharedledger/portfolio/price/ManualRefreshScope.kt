package com.sephilabs.sharedledger.portfolio.price

import java.time.Instant
import java.util.UUID

/** Narrows a user-triggered run to the coordinates [householdId] holds. A series fetched at or after
 *  [skipFetchedSince] is left alone, and with [equitiesStaleOnly] (quota-limited providers) an equity series
 *  is fetched only while it is missing or stale. */
data class ManualRefreshScope(
    val householdId: UUID,
    val skipFetchedSince: Instant,
    val equitiesStaleOnly: Boolean,
)
