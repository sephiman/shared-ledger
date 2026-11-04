package com.sephilabs.sharedledger.bank.syncschedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One or two daily wall-clock sync times in [zone], so 07:00 stays 07:00 across a DST change. */
data class SyncTimes(val times: List<LocalTime>, val zone: ZoneId) {

    init {
        require(times.size in 1..MAX_TIMES) { "one or two sync times" }
    }

    /** True when one of the times falls in the slot that ends at [tick], i.e. in `(tick − SLOT, tick]`. */
    fun isDueAt(tick: Instant): Boolean {
        val windowStart = tick.minus(SLOT)
        val tickDate = tick.atZone(zone).toLocalDate()
        // The previous day too: a slot can straddle local midnight.
        return listOf(tickDate.minusDays(1), tickDate).any { date ->
            instantsOn(date).any { it.isAfter(windowStart) && !it.isAfter(tick) }
        }
    }

    /** The next [count] fire instants strictly after [from]. */
    fun nextRuns(from: Instant, count: Int): List<Instant> =
        generateSequence(from.atZone(zone).toLocalDate()) { it.plusDays(1) }
            .flatMap { instantsOn(it).sorted() }
            .filter { it.isAfter(from) }
            .take(count)
            .toList()

    // A time skipped by spring-forward resolves to the shifted instant; a repeated one to its first occurrence.
    private fun instantsOn(date: LocalDate): List<Instant> = times.map { ZonedDateTime.of(date, it, zone).toInstant() }

    companion object {
        const val MAX_TIMES = 2

        // Must match the tick cadence of app.enable-banking.sync-tick-cron.
        val SLOT: Duration = Duration.ofMinutes(15)

        // Two runs closer than this add little freshness and only spend the daily call budget.
        val MIN_GAP: Duration = Duration.ofHours(2)

        val DEFAULT = SyncTimes(listOf(LocalTime.of(7, 0), LocalTime.of(19, 0)), ZoneId.of("UTC"))

        /** The slot boundary at or before [now], so a tick that fires a little late still checks its own slot. */
        fun slotEndingAt(now: Instant): Instant {
            val slotSeconds = SLOT.seconds
            return Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, slotSeconds))
        }

        fun isOnSlotGrid(time: LocalTime): Boolean =
            time.second == 0 && time.nano == 0 && time.minute % SLOT.toMinutes().toInt() == 0
    }
}
