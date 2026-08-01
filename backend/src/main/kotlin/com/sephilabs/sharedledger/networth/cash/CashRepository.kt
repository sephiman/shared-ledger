package com.sephilabs.sharedledger.networth.cash

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface CashAdjustmentRepository : JpaRepository<CashAdjustment, UUID> {

    /** Adjustment series newest-first for the Cash sub-tab. */
    fun findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(householdId: UUID): List<CashAdjustment>

    /** The anchor as of [date]: the latest adjustment on or before it, same-day ties broken by createdAt
     *  (last created wins). Null if none exists yet. */
    fun findFirstByHouseholdIdAndAdjustmentDateLessThanEqualOrderByAdjustmentDateDescCreatedAtDesc(
        householdId: UUID,
        date: LocalDate,
    ): CashAdjustment?

    /** Hard-delete every adjustment of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction). */
    @Modifying
    @Query(value = "DELETE FROM cash_adjustments WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}

interface CashEstimateSettingsRepository : JpaRepository<CashEstimateSettings, UUID> {

    @Modifying
    @Query(value = "DELETE FROM cash_estimate_settings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
