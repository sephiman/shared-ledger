package com.sharedledger.loan

import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

/**
 * Materializes expected loan payments from active schedules into the loan_payments table.
 *
 * NOT a @Scheduled bean by itself — the existing RecurringMaterializer.runDaily() invokes
 * runForAll(today) so there is one cron entrypoint for daily materialization.
 * Idempotency comes from the unique partial index (schedule_id, payment_date).
 */
@Component
class LoanScheduleMaterializer(
    private val loans: LoanRepository,
    private val schedules: LoanScheduleRepository,
    private val payments: LoanPaymentRepository,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(LoanScheduleMaterializer::class.java)

    fun runForAll(today: LocalDate): Int {
        val active = schedules.findAllByActiveTrue()
        log.info("Running loan schedule materializer for {} active schedules", active.size)
        var totalCreated = 0
        for (schedule in active) {
            totalCreated += materializeOne(schedule, today)
        }
        return totalCreated
    }

    fun fireNow(householdId: UUID, loanId: UUID, by: User, today: LocalDate): Int {
        val loan = loans.findById(loanId).orElseThrow { AppException.notFound("LOAN_NOT_FOUND") }
        if (loan.householdId != householdId) throw AppException.notFound("LOAN_NOT_FOUND")
        if (loan.status != LoanStatus.active) return 0
        val schedule = schedules.findByLoanId(loan.id) ?: throw AppException.notFound("LOAN_SCHEDULE_NOT_FOUND")
        return materializeOne(schedule, today, fallbackUserId = by.id)
    }

    private fun materializeOne(schedule: LoanSchedule, today: LocalDate, fallbackUserId: UUID? = null): Int {
        val scheduleId = schedule.id.toString()
        MDC.put("loanScheduleId", scheduleId)
        try {
            val loan = loans.findById(schedule.loanId).orElse(null) ?: return 0
            if (loan.deletedAt != null || loan.status != LoanStatus.active) return 0

            val from = schedule.lastMaterializedThrough?.plusDays(1) ?: loan.startDate
            if (from.isAfter(today)) return 0

            val dates = LoanScheduleDateMath.occurrencesInRange(schedule, loan.startDate, from, today)
            if (dates.isEmpty()) {
                runInTx {
                    val refreshed = schedules.findById(schedule.id).orElse(schedule)
                    refreshed.lastMaterializedThrough = today
                    schedules.save(refreshed)
                }
                return 0
            }

            val userId = fallbackUserId ?: loan.updatedByUserId
            var created = 0
            for (date in dates) {
                try {
                    runInTx {
                        if (payments.existsByScheduleIdAndPaymentDate(schedule.id, date)) return@runInTx
                        val payment = LoanPayment(
                            loanId = loan.id,
                            paymentDate = date,
                            amount = schedule.expectedAmount,
                            description = null,
                            scheduleId = schedule.id,
                            createdByUserId = userId,
                            updatedByUserId = userId,
                        )
                        payments.save(payment)
                    }
                    created++
                } catch (_: DataIntegrityViolationException) {
                    log.debug("Skipped duplicate loan payment on {} for schedule {}", date, scheduleId)
                }
            }
            runInTx {
                val refreshed = schedules.findById(schedule.id).orElse(schedule)
                refreshed.lastMaterializedThrough = today
                schedules.save(refreshed)
            }
            return created
        } catch (ex: Exception) {
            log.error("Loan schedule materialization failed for schedule {}", scheduleId, ex)
            return 0
        } finally {
            MDC.remove("loanScheduleId")
        }
    }

    private fun runInTx(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }
}
