package com.sephilabs.sharedledger.portfolio

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
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

    // The provider-symbol guard keeps a run that started before a relink from flagging the new symbol.
    @Modifying
    @Query(
        "UPDATE Holding h SET h.providerNotFoundSince = :since " +
            "WHERE h.id IN :ids AND h.providerSymbol = :providerSymbol AND h.providerNotFoundSince IS NULL"
    )
    fun markProviderNotFound(
        @Param("ids") ids: Collection<UUID>,
        @Param("providerSymbol") providerSymbol: String,
        @Param("since") since: LocalDate,
    ): Int

    @Modifying
    @Query(
        "UPDATE Holding h SET h.providerNotFoundSince = NULL " +
            "WHERE h.id IN :ids AND h.providerSymbol = :providerSymbol AND h.providerNotFoundSince IS NOT NULL"
    )
    fun clearProviderNotFound(
        @Param("ids") ids: Collection<UUID>,
        @Param("providerSymbol") providerSymbol: String,
    ): Int
}
