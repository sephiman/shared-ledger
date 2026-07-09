package com.sephilabs.sharedledger.lending

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface LendingRepository : JpaRepository<Lending, UUID> {

    /** Hard-delete every lending of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction).
     *  lending_schedules and lending_payments are removed via ON DELETE CASCADE. */
    @Modifying
    @Query(value = "DELETE FROM lendings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdId(householdId: UUID): List<Lending>
    fun findAllByHouseholdIdAndStatus(householdId: UUID, status: LendingStatus): List<Lending>
}

interface LendingPaymentRepository : JpaRepository<LendingPayment, UUID> {
    fun findAllByLendingIdOrderByPaymentDateAsc(lendingId: UUID): List<LendingPayment>

    @Query(
        """
        SELECT p FROM LendingPayment p
        WHERE p.lendingId IN :lendingIds
        ORDER BY p.paymentDate ASC
        """
    )
    fun findAllByLendingIdsOrderByPaymentDateAsc(@Param("lendingIds") lendingIds: Collection<UUID>): List<LendingPayment>

    fun existsByScheduleIdAndPaymentDate(scheduleId: UUID, paymentDate: LocalDate): Boolean
}

interface LendingScheduleRepository : JpaRepository<LendingSchedule, UUID> {
    fun findByLendingId(lendingId: UUID): LendingSchedule?
    fun findAllByActiveTrue(): List<LendingSchedule>
}
