package com.sephilabs.sharedledger.bank.syncschedule

import com.sephilabs.sharedledger.common.errors.AppException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Service
class BankSyncScheduleService(private val schedules: BankSyncScheduleRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun timesFor(householdId: UUID): SyncTimes =
        schedules.findById(householdId).orElse(null)?.toSyncTimes() ?: SyncTimes.DEFAULT

    @Transactional(readOnly = true)
    fun isCustom(householdId: UUID): Boolean = schedules.existsById(householdId)

    @Transactional
    fun update(householdId: UUID, times: List<LocalTime>, timezone: String, byUserId: UUID): SyncTimes {
        val validated = validate(times, timezone)
        val sorted = validated.times.sorted()
        val row = schedules.findById(householdId).orElse(null)
            ?: BankSyncSchedule(householdId = householdId, firstTime = sorted.first(), timezone = timezone)
        row.firstTime = sorted.first()
        row.secondTime = sorted.getOrNull(1)
        row.timezone = validated.zone.id
        row.updatedByUserId = byUserId
        row.updatedAt = Instant.now()
        schedules.save(row)
        log.info("bank_sync_schedule_updated household={} times={} zone={}", householdId, sorted, validated.zone.id)
        return row.toSyncTimes()
    }

    @Transactional
    fun resetToDefault(householdId: UUID) {
        schedules.hardDeleteAllByHouseholdId(householdId)
        log.info("bank_sync_schedule_reset household={}", householdId)
    }

    /** Loads every custom schedule once, so a tick over many connections costs one query. */
    @Transactional(readOnly = true)
    fun dueAt(tick: Instant): (UUID) -> Boolean {
        val custom = schedules.findAll().associate { it.householdId to it.toSyncTimes() }
        val defaultDue = SyncTimes.DEFAULT.isDueAt(tick)
        val customDue = custom.mapValues { (_, times) -> times.isDueAt(tick) }
        return { householdId -> customDue[householdId] ?: defaultDue }
    }

    private fun validate(times: List<LocalTime>, timezone: String): SyncTimes {
        val zone = try {
            ZoneId.of(timezone)
        } catch (_: DateTimeException) {
            throw AppException.badRequest("BANK_SYNC_TIMEZONE_INVALID", timezone)
        }
        if (times.size !in 1..SyncTimes.MAX_TIMES || !times.all(SyncTimes::isOnSlotGrid)) {
            throw AppException.badRequest("BANK_SYNC_TIMES_INVALID")
        }
        if (times.size == 2 && circularGap(times[0], times[1]) < SyncTimes.MIN_GAP) {
            throw AppException.badRequest("BANK_SYNC_TIMES_TOO_CLOSE", SyncTimes.MIN_GAP.toHours())
        }
        return SyncTimes(times, zone)
    }

    // 23:00 and 00:30 are 90 minutes apart, not 22.5 hours.
    private fun circularGap(a: LocalTime, b: LocalTime): Duration {
        val direct = Duration.between(a, b).abs()
        return minOf(direct, Duration.ofDays(1).minus(direct))
    }
}
