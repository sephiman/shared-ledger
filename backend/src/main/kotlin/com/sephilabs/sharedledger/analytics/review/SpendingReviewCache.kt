package com.sephilabs.sharedledger.analytics.review

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Fingerprint-keyed LRU cache for deterministic savings candidates, matching FIRE's `FireSectionCache`.
 *  Any change to transactions, recurring templates, categories, bank links, or amortization entries
 *  produces a different fingerprint so entries never go stale. */
@Component
class SpendingReviewCache(
    private val queries: SpendingReviewDataQueries,
) {

    private data class Key(
        val householdId: UUID,
        val year: Int,
        val month: Int,
        val dataFingerprint: String,
        val today: LocalDate,
    )

    private val entries = object : LinkedHashMap<Key, SpendingReviewCandidatesResponse>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, SpendingReviewCandidatesResponse>?): Boolean =
            size > MAX_ENTRIES
    }

    fun getOrCompute(
        householdId: UUID,
        year: Int,
        month: Int,
        today: LocalDate,
        compute: () -> SpendingReviewCandidatesResponse,
    ): SpendingReviewCandidatesResponse {
        val key = Key(householdId, year, month, queries.dataFingerprint(householdId), today)
        synchronized(entries) { entries[key] }?.let { return it }
        val computed = compute()
        synchronized(entries) { entries[key] = computed }
        return computed
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
