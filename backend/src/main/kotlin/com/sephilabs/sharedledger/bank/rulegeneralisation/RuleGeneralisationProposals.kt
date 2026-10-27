package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.transaction.Direction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A proposal as the run left it. [absorbedValues] travel with it so a card can still name its rules after
 *  one of them is gone. */
data class StoredProposal(
    val id: String,
    val pattern: String,
    val field: RuleField,
    val direction: Direction,
    val categoryCode: String,
    val absorbedRuleIds: List<UUID>,
    val absorbedValues: List<String>,
    val confidence: Double,
)

/** Proposals live in this instance's memory only, keyed by run, for [ttl]: never persisted, gone on restart.
 *  Assumes a single backend instance, as the rest of the app does. */
class RuleGeneralisationProposals(private val clock: Clock, private val ttl: Duration) {

    private class Entry(val householdId: UUID, val proposals: List<StoredProposal>, val expiresAt: Instant)

    private val byRun = ConcurrentHashMap<UUID, Entry>()

    fun put(runId: UUID, householdId: UUID, proposals: List<StoredProposal>) {
        evictExpired()
        byRun[runId] = Entry(householdId, proposals, clock.instant().plus(ttl))
    }

    /** null once expired, or for another household's run. */
    fun get(runId: UUID, householdId: UUID): List<StoredProposal>? {
        evictExpired()
        return byRun[runId]?.takeIf { it.householdId == householdId }?.proposals
    }

    private fun evictExpired() {
        val now = clock.instant()
        byRun.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    }
}
