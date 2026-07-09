package com.sephilabs.sharedledger.loan

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface LoanRepository : JpaRepository<Loan, UUID> {

    /** Hard-delete every loan of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction).
     *  loan_schedules and loan_payments are removed via ON DELETE CASCADE. */
    @Modifying
    @Query(value = "DELETE FROM loans WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdId(householdId: UUID): List<Loan>
    fun findAllByHouseholdIdAndStatus(householdId: UUID, status: LoanStatus): List<Loan>
}

interface LoanPaymentRepository : JpaRepository<LoanPayment, UUID> {
    fun findAllByLoanIdOrderByPaymentDateAsc(loanId: UUID): List<LoanPayment>

    @Query(
        """
        SELECT p FROM LoanPayment p
        WHERE p.loanId IN :loanIds
        ORDER BY p.paymentDate ASC
        """
    )
    fun findAllByLoanIdsOrderByPaymentDateAsc(@Param("loanIds") loanIds: Collection<UUID>): List<LoanPayment>

    fun existsByScheduleIdAndPaymentDate(scheduleId: UUID, paymentDate: LocalDate): Boolean
}

interface LoanScheduleRepository : JpaRepository<LoanSchedule, UUID> {
    fun findByLoanId(loanId: UUID): LoanSchedule?
    fun findAllByActiveTrue(): List<LoanSchedule>
}
