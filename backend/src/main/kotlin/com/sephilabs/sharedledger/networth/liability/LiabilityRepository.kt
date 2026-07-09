package com.sephilabs.sharedledger.networth.liability

import com.sephilabs.sharedledger.networth.IdNameRow
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface LiabilityRepository : JpaRepository<Liability, UUID> {

    /** Hard-delete every liability of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction).
     *  amortization_parts (and their entries/prepayments/revisions) and liability_balance_entries are
     *  removed via ON DELETE CASCADE. Callers must delete snapshots and net-worth movements first,
     *  since snapshot_liability_balances / net_worth_movements reference liabilities without cascade. */
    @Modifying
    @Query(value = "DELETE FROM liabilities WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdIdOrderByNameAsc(householdId: UUID): List<Liability>
    fun findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId: UUID): List<Liability>
    fun findAllByAmortizableTrueAndActiveTrue(): List<Liability>

    /** id -> name for every liability of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction). */
    @Query(value = "SELECT id AS id, name AS name FROM liabilities WHERE household_id = :householdId", nativeQuery = true)
    fun findAllNamesIncludingDeleted(householdId: UUID): List<IdNameRow>
}

interface LiabilityBalanceEntryRepository : JpaRepository<LiabilityBalanceEntry, UUID> {
    fun findAllByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(liabilityId: UUID): List<LiabilityBalanceEntry>

    /** Latest balance with balanceDate on or before [date] — the value the series reports at [date]. */
    fun findFirstByLiabilityIdAndBalanceDateLessThanEqualOrderByBalanceDateDescCreatedAtDesc(
        liabilityId: UUID,
        date: LocalDate,
    ): LiabilityBalanceEntry?

    fun findFirstByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(liabilityId: UUID): LiabilityBalanceEntry?
}
