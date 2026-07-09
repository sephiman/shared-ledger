package com.sephilabs.sharedledger.networth.asset

import com.sephilabs.sharedledger.networth.IdNameRow
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface AssetRepository : JpaRepository<Asset, UUID> {

    /** Hard-delete every asset of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction).
     *  asset_value_entries are removed via ON DELETE CASCADE. */
    @Modifying
    @Query(value = "DELETE FROM assets WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdIdOrderByNameAsc(householdId: UUID): List<Asset>
    fun findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId: UUID): List<Asset>

    /** id -> name for every asset of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction). */
    @Query(value = "SELECT id AS id, name AS name FROM assets WHERE household_id = :householdId", nativeQuery = true)
    fun findAllNamesIncludingDeleted(householdId: UUID): List<IdNameRow>
}

interface AssetValueEntryRepository : JpaRepository<AssetValueEntry, UUID> {
    fun findAllByAssetIdOrderByValueDateDescCreatedAtDesc(assetId: UUID): List<AssetValueEntry>

    /** Latest value with valueDate on or before [date] — the value the series reports at [date]. */
    fun findFirstByAssetIdAndValueDateLessThanEqualOrderByValueDateDescCreatedAtDesc(
        assetId: UUID,
        date: LocalDate,
    ): AssetValueEntry?

    fun findFirstByAssetIdOrderByValueDateDescCreatedAtDesc(assetId: UUID): AssetValueEntry?
}
