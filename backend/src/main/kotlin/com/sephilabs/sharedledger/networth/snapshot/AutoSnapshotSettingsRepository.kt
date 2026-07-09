package com.sephilabs.sharedledger.networth.snapshot

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AutoSnapshotSettingsRepository : JpaRepository<AutoSnapshotSettings, UUID> {
    fun findAllByEnabledTrue(): List<AutoSnapshotSettings>

    @Modifying
    @Query(value = "DELETE FROM auto_snapshot_settings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
