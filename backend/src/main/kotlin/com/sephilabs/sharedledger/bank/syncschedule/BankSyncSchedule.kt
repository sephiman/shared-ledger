package com.sephilabs.sharedledger.bank.syncschedule

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** A household's own background-sync times; absent means [SyncTimes.DEFAULT]. */
@Entity
@Table(name = "bank_sync_schedules")
class BankSyncSchedule(
    @Id
    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "first_time", nullable = false)
    var firstTime: LocalTime,

    @Column(name = "second_time")
    var secondTime: LocalTime? = null,

    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun toSyncTimes() = SyncTimes(listOfNotNull(firstTime, secondTime), ZoneId.of(timezone))
}
