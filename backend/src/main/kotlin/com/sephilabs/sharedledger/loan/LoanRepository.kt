package com.sephilabs.sharedledger.loan

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface LoanRepository : JpaRepository<Loan, UUID> {
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
