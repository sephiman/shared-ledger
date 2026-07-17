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

interface FireTaxBracketRepository : JpaRepository<FireTaxBracket, FireTaxBracketId> {

    @Query("SELECT b FROM FireTaxBracket b WHERE b.id.householdId = :hid ORDER BY b.id.lowerBound ASC")
    fun findAllForHousehold(@Param("hid") householdId: UUID): List<FireTaxBracket>

    @Modifying
    @Query(value = "DELETE FROM fire_tax_brackets WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
