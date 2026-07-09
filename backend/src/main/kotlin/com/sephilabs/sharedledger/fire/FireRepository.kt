package com.sephilabs.sharedledger.fire

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FireSettingsRepository : JpaRepository<FireSettings, UUID> {

    @Modifying
    @Query(value = "DELETE FROM fire_settings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
