package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.portfolio.Holding
import java.time.LocalDate

/** One provider coordinate and every linked holding priced off it. [closedOn] is null while any of them is
 *  open (or has no lots yet); otherwise it is the last trade day, after which no price can move a valuation. */
data class CoordinateDemand(
    val providerSymbol: String,
    val currency: String,
    val holdings: List<Holding>,
    val earliestLot: LocalDate?,
    val closedOn: LocalDate?,
) {
    val open: Boolean get() = closedOn == null

    // A closed series is fetched a week past the close so a holiday sell date still lands on a stored day.
    fun fetchUntil(horizon: LocalDate): LocalDate =
        closedOn?.plusDays(CLOSED_TAIL_MARGIN_DAYS)?.coerceAtMost(horizon) ?: horizon

    fun needsTail(maxStored: LocalDate, horizon: LocalDate): Boolean =
        maxStored.isBefore(closedOn ?: horizon)

    companion object {
        const val CLOSED_TAIL_MARGIN_DAYS: Long = 7
    }
}
