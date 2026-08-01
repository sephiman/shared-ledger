package com.sephilabs.sharedledger.portfolio

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface HoldingRepository : JpaRepository<Holding, UUID> {

    /** Hard-delete every holding incl. soft-deleted (native, bypasses @SQLRestriction); lots and valuations cascade.
     *  Hard-delete every holding incl. soft-deleted (native, bypasses @SQLRestriction); lots and valuations cascade. */
    @Modifying
    @Query(value = "DELETE FROM holdings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdIdOrderBySymbolAsc(householdId: UUID): List<Holding>

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): Holding?

    fun existsByHouseholdIdAndAssetClassAndSymbol(
        householdId: UUID,
        assetClass: HoldingAssetClass,
        symbol: String,
    ): Boolean

    @Query("SELECT h FROM Holding h WHERE h.provider IS NOT NULL AND h.active = TRUE")
    fun findAllLinkedActive(): List<Holding>

    @Query("SELECT DISTINCT h.nativeCurrency FROM Holding h WHERE h.nativeCurrency <> :base AND h.provider IS NOT NULL")
    fun findDistinctLinkedForeignNativeCurrencies(@Param("base") base: String): List<String>
}
