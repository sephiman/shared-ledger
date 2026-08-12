package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.common.Money
import java.math.BigDecimal
import java.time.LocalDate

/** The natural key of a transaction as it appears in a CSV: what the importer skips duplicates by, and
 *  what a refund row's `refund_of_key` points at. The id is not exported, so this is the only thing a
 *  cross-file reference can name — it lives here rather than inside the importer because the exporter has
 *  to produce byte-identical keys. */
object TransactionKeys {

    fun dedupKey(
        date: LocalDate,
        direction: Direction,
        categoryCode: String,
        amount: BigDecimal,
        description: String?,
    ): String {
        val desc = description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return "$date|${direction.name}|$categoryCode|${Money.normalize(amount).toPlainString()}|$desc"
    }

    fun dedupKey(tx: Transaction): String =
        dedupKey(tx.occurrenceDate, tx.direction, tx.categoryCode, tx.amount, tx.description)

    /** The booking date a key was built from, or null when the text isn't one of our keys. */
    fun dateOf(key: String): LocalDate? =
        runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()
}
