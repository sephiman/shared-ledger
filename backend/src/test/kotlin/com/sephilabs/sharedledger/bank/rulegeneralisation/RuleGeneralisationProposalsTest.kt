package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class RuleGeneralisationProposalsTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
    }

    private val clock = MovableClock(Instant.parse("2026-10-27T10:00:00Z"))
    private val store = RuleGeneralisationProposals(clock, Duration.ofMinutes(30))
    private val proposal = StoredProposal("p1", "Lidl", RuleField.counterparty, Direction.expense, "groceries.groceries", emptyList(), emptyList(), 0.9)

    @Test
    fun `proposals are there for thirty minutes and gone after`() {
        val run = UUID.randomUUID()
        val household = UUID.randomUUID()
        store.put(run, household, listOf(proposal))

        clock.now = clock.now.plus(Duration.ofMinutes(29))
        assertThat(store.get(run, household)).containsExactly(proposal)

        clock.now = clock.now.plus(Duration.ofMinutes(1))
        assertThat(store.get(run, household)).isNull()
    }

    @Test
    fun `another household never reads a run's proposals`() {
        val run = UUID.randomUUID()
        store.put(run, UUID.randomUUID(), listOf(proposal))

        assertThat(store.get(run, UUID.randomUUID())).isNull()
    }
}
