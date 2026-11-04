package com.sephilabs.sharedledger.bank.syncschedule

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface BankSyncScheduleRepository : JpaRepository<BankSyncSchedule, UUID> {
    @Modifying
    @Query(value = "DELETE FROM bank_sync_schedules WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
