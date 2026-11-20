package com.sephilabs.sharedledger.ai

import com.sephilabs.sharedledger.fire.FireDefaults
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import java.time.YearMonth
import java.util.UUID

/** Shared stale-spending rule used by FIRE and Analytics spending reviews: returns the last month with
 *  any expense when the latest [FireDefaults.STALE_SPENDING_MONTHS] complete months of the window have none. */
object SpendingStaleness {

    fun noSpendingSince(
        transactions: TransactionRepository,
        householdId: UUID,
        windowEnd: YearMonth,
        asOfMonth: YearMonth,
    ): YearMonth? {
        val lastComplete = if (windowEnd == asOfMonth) asOfMonth.minusMonths(1) else windowEnd
        val firstChecked = lastComplete.minusMonths(FireDefaults.STALE_SPENDING_MONTHS - 1L)
        val lastExpense = transactions.lastDateOf(householdId, Direction.expense, windowEnd.atEndOfMonth()) ?: return null
        return YearMonth.from(lastExpense).takeIf { it.isBefore(firstChecked) }
    }
}
