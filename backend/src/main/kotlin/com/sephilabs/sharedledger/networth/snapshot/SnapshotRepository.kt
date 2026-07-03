package com.sephilabs.sharedledger.networth.snapshot

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface SnapshotRepository : JpaRepository<Snapshot, UUID> {

    @Modifying
    @Query(value = """
        DELETE FROM snapshot_liability_balances
        WHERE snapshot_id IN (SELECT id FROM snapshots WHERE household_id = :hid)
    """, nativeQuery = true)
    fun deleteAllLiabilityBalancesByHouseholdId(@Param("hid") householdId: UUID): Int

    @Modifying
    @Query(value = "DELETE FROM snapshots WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    @Query("""
        SELECT s FROM Snapshot s
        WHERE s.householdId = :hid
          AND s.snapshotDate BETWEEN :from AND :to
        ORDER BY s.snapshotDate ASC, s.createdAt ASC
    """)
    fun findInRange(@Param("hid") householdId: UUID, @Param("from") from: LocalDate, @Param("to") to: LocalDate): List<Snapshot>

    @Query("""
        SELECT s FROM Snapshot s
        WHERE s.householdId = :hid AND s.snapshotDate <= :asOf
        ORDER BY s.snapshotDate DESC, s.createdAt DESC
    """)
    fun findUpTo(@Param("hid") householdId: UUID, @Param("asOf") asOf: LocalDate): List<Snapshot>

    @Query("""
        SELECT s FROM Snapshot s
        WHERE s.householdId = :hid
        ORDER BY s.snapshotDate DESC, s.createdAt DESC
        LIMIT 1
    """)
    fun findLatest(@Param("hid") householdId: UUID): Snapshot?

    @Query("""
        SELECT s FROM Snapshot s
        WHERE s.householdId = :hid
        ORDER BY s.snapshotDate ASC, s.createdAt ASC
    """)
    fun findAllOrdered(@Param("hid") householdId: UUID): List<Snapshot>

    fun existsByHouseholdIdAndSnapshotDate(householdId: UUID, snapshotDate: LocalDate): Boolean
}
