package com.sephilabs.sharedledger.bank.syncschedule

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class SyncTimesTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun times(vararg hhmm: String, zone: ZoneId = madrid) = SyncTimes(hhmm.map(LocalTime::parse), zone)

    @Test
    fun `the default fires at 07 00 and 19 00 UTC and nowhere else in the day`() {
        val dueTicks = generateSequence(Instant.parse("2026-11-04T00:00:00Z")) { it.plus(SyncTimes.SLOT) }
            .takeWhile { it.isBefore(Instant.parse("2026-11-05T00:00:00Z")) }
            .filter(SyncTimes.DEFAULT::isDueAt)
            .toList()

        assertThat(dueTicks).containsExactly(Instant.parse("2026-11-04T07:00:00Z"), Instant.parse("2026-11-04T19:00:00Z"))
    }

    @Test
    fun `a local time keeps its wall clock across the summer and winter offsets`() {
        val schedule = times("08:00")

        assertThat(schedule.isDueAt(Instant.parse("2026-11-04T07:00:00Z"))).isTrue() // CET, UTC+1
        assertThat(schedule.isDueAt(Instant.parse("2026-07-01T06:00:00Z"))).isTrue() // CEST, UTC+2
        assertThat(schedule.isDueAt(Instant.parse("2026-07-01T07:00:00Z"))).isFalse()
    }

    @Test
    fun `a time skipped by spring-forward fires once at the shifted instant`() {
        // 2026-03-29 02:00 CET jumps to 03:00 CEST, so 02:30 resolves to 03:30 CEST = 01:30Z.
        val schedule = times("02:30")

        assertThat(schedule.isDueAt(Instant.parse("2026-03-29T01:30:00Z"))).isTrue()
        assertThat(schedule.isDueAt(Instant.parse("2026-03-29T00:30:00Z"))).isFalse()
    }

    @Test
    fun `a time repeated by fall-back fires only on its first occurrence`() {
        // 2026-10-25 03:00 CEST falls back to 02:00 CET, so 02:30 happens at 00:30Z and again at 01:30Z.
        val schedule = times("02:30")

        assertThat(schedule.isDueAt(Instant.parse("2026-10-25T00:30:00Z"))).isTrue()
        assertThat(schedule.isDueAt(Instant.parse("2026-10-25T01:30:00Z"))).isFalse()
    }

    @Test
    fun `local midnight fires on the UTC day before`() {
        assertThat(times("00:00").isDueAt(Instant.parse("2026-11-03T23:00:00Z"))).isTrue()
    }

    @Test
    fun `a zone with a 45-minute offset lines up with the tick grid`() {
        val kathmandu = times("06:00", zone = ZoneId.of("Asia/Kathmandu")) // UTC+05:45

        assertThat(kathmandu.isDueAt(Instant.parse("2026-11-04T00:15:00Z"))).isTrue()
    }

    @Test
    fun `a late tick still checks the slot it belongs to`() {
        val tick = SyncTimes.slotEndingAt(Instant.parse("2026-11-04T07:03:20Z"))

        assertThat(tick).isEqualTo(Instant.parse("2026-11-04T07:00:00Z"))
        assertThat(SyncTimes.DEFAULT.isDueAt(tick)).isTrue()
    }

    @Test
    fun `next runs are the upcoming instants in order across days`() {
        val runs = times("21:00", "06:30").nextRuns(Instant.parse("2026-11-04T10:00:00Z"), count = 3)

        assertThat(runs).containsExactly(
            Instant.parse("2026-11-04T20:00:00Z"),
            Instant.parse("2026-11-05T05:30:00Z"),
            Instant.parse("2026-11-05T20:00:00Z"),
        )
    }

    @Test
    fun `only quarter-hour times are on the grid`() {
        assertThat(SyncTimes.isOnSlotGrid(LocalTime.of(7, 45))).isTrue()
        assertThat(SyncTimes.isOnSlotGrid(LocalTime.of(7, 10))).isFalse()
        assertThat(SyncTimes.isOnSlotGrid(LocalTime.of(7, 0, 30))).isFalse()
    }
}
