package com.sharedledger.loan

import com.sharedledger.common.Csv
import com.sharedledger.common.Money
import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.user.User
import com.sharedledger.notification.NotifyAction
import com.sharedledger.notification.NotifyActor
import com.sharedledger.notification.NotificationPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class LoanService(
    private val loans: LoanRepository,
    private val payments: LoanPaymentRepository,
    private val schedules: LoanScheduleRepository,
    private val notifications: NotificationPublisher,
) {

    @Transactional
    fun create(householdId: UUID, request: LoanRequest, by: User): Loan {
        validateLoan(request)
        val loan = Loan(
            householdId = householdId,
            borrowerName = request.borrowerName.trim(),
            principalAmount = Money.normalize(request.principalAmount),
            startDate = request.startDate,
            description = request.description?.takeIf { it.isNotBlank() },
            interestType = request.interestType,
            annualInterestRate = request.annualInterestRate?.let { normalizeRate(it) },
            compoundingPeriod = request.compoundingPeriod,
            status = LoanStatus.active,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        return loans.save(loan)
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: LoanRequest, by: User): Loan {
        validateLoan(request)
        val loan = loadOwn(householdId, id)
        if (loan.status != LoanStatus.active) {
            throw AppException.badRequest("LOAN_NOT_ACTIVE")
        }
        loan.borrowerName = request.borrowerName.trim()
        loan.principalAmount = Money.normalize(request.principalAmount)
        loan.startDate = request.startDate
        loan.description = request.description?.takeIf { it.isNotBlank() }
        loan.interestType = request.interestType
        loan.annualInterestRate = request.annualInterestRate?.let { normalizeRate(it) }
        loan.compoundingPeriod = request.compoundingPeriod
        loan.updatedByUserId = by.id
        return loan
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val loan = loadOwn(householdId, id)
        loan.deletedAt = Instant.now()
        loan.updatedByUserId = by.id
    }

    @Transactional
    fun settle(householdId: UUID, id: UUID, request: LoanStatusTransitionRequest, by: User): Loan =
        transitionStatus(householdId, id, LoanStatus.settled, request.closedDate, by)

    @Transactional
    fun writeOff(householdId: UUID, id: UUID, request: LoanStatusTransitionRequest, by: User): Loan =
        transitionStatus(householdId, id, LoanStatus.written_off, request.closedDate, by)

    @Transactional
    fun reopen(householdId: UUID, id: UUID, by: User): Loan {
        val loan = loadOwn(householdId, id)
        if (loan.status == LoanStatus.active) return loan
        loan.status = LoanStatus.active
        loan.closedDate = null
        loan.updatedByUserId = by.id
        return loan
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): LoanDetail {
        val loan = loadOwn(householdId, id)
        val loanPayments = payments.findAllByLoanIdOrderByPaymentDateAsc(loan.id)
        val schedule = schedules.findByLoanId(loan.id)
        val outstanding = LoanBalanceCalculator.compute(loan, loanPayments, LocalDate.now())
        return LoanDetail(
            summary = toSummary(loan, outstanding, schedule),
            payments = mergePaymentDtos(loanPayments, outstanding),
            schedule = schedule?.toDto(),
        )
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, status: String?, top: Int?): LoanListResponse {
        val all = when (status?.lowercase()) {
            "active" -> loans.findAllByHouseholdIdAndStatus(householdId, LoanStatus.active)
            "settled" -> loans.findAllByHouseholdIdAndStatus(householdId, LoanStatus.settled)
            "written_off" -> loans.findAllByHouseholdIdAndStatus(householdId, LoanStatus.written_off)
            null, "", "all" -> loans.findAllByHouseholdId(householdId)
            else -> throw AppException.badRequest("LOAN_STATUS_INVALID")
        }
        if (all.isEmpty()) {
            return LoanListResponse(
                loans = emptyList(),
                totalOutstandingActive = BigDecimal.ZERO,
                activeCount = 0,
                top = emptyList(),
            )
        }
        val ids = all.map { it.id }
        val paymentsByLoan = payments.findAllByLoanIdsOrderByPaymentDateAsc(ids).groupBy { it.loanId }
        val scheduleByLoan = schedules.findAll()
            .filter { it.loanId in ids.toHashSet() }
            .associateBy { it.loanId }
        val now = LocalDate.now()
        val summaries = all.map { loan ->
            val outstanding = LoanBalanceCalculator.compute(
                loan,
                paymentsByLoan[loan.id].orEmpty(),
                now,
            )
            toSummary(loan, outstanding, scheduleByLoan[loan.id])
        }.sortedWith(
            compareByDescending<LoanSummary> { it.status == LoanStatus.active }
                .thenByDescending { it.totalOutstanding }
                .thenBy { it.borrowerName.lowercase() }
        )
        val active = summaries.filter { it.status == LoanStatus.active }
        val total = active.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.totalOutstanding) }
        val topN = (top ?: 0).coerceAtLeast(0)
        val topList = if (topN == 0) emptyList() else active.take(topN)
        return LoanListResponse(
            loans = summaries,
            totalOutstandingActive = Money.normalize(total),
            activeCount = active.size,
            top = topList,
        )
    }

    @Transactional
    fun registerPayment(householdId: UUID, loanId: UUID, request: LoanPaymentRequest, by: User): LoanPayment {
        val loan = loadOwn(householdId, loanId)
        validatePayment(loan, request.paymentDate, request.amount)
        val payment = LoanPayment(
            loanId = loan.id,
            paymentDate = request.paymentDate,
            amount = Money.normalize(request.amount),
            description = request.description?.takeIf { it.isNotBlank() },
            scheduleId = null,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        payments.save(payment)
        notifications.loanPayment(payment, loan.householdId, loan.borrowerName, NotifyAction.CREATE, NotifyActor.Human(by.email))
        return payment
    }

    @Transactional
    fun updatePayment(
        householdId: UUID,
        loanId: UUID,
        paymentId: UUID,
        request: LoanPaymentRequest,
        by: User,
    ): LoanPayment {
        val loan = loadOwn(householdId, loanId)
        val payment = loadPayment(loan.id, paymentId)
        validatePayment(loan, request.paymentDate, request.amount)
        payment.paymentDate = request.paymentDate
        payment.amount = Money.normalize(request.amount)
        payment.description = request.description?.takeIf { it.isNotBlank() }
        payment.updatedByUserId = by.id
        notifications.loanPayment(payment, loan.householdId, loan.borrowerName, NotifyAction.UPDATE, NotifyActor.Human(by.email))
        return payment
    }

    @Transactional
    fun deletePayment(householdId: UUID, loanId: UUID, paymentId: UUID, by: User) {
        val loan = loadOwn(householdId, loanId)
        val payment = loadPayment(loan.id, paymentId)
        payment.deletedAt = Instant.now()
        payment.updatedByUserId = by.id
        notifications.loanPayment(payment, loan.householdId, loan.borrowerName, NotifyAction.DELETE, NotifyActor.Human(by.email))
    }

    @Transactional(readOnly = true)
    fun previewSplit(
        householdId: UUID,
        loanId: UUID,
        proposedDate: LocalDate,
        proposedAmount: BigDecimal,
        excludePaymentId: UUID? = null,
    ): PaymentSplitPreview {
        val loan = loadOwn(householdId, loanId)
        val existing = payments.findAllByLoanIdOrderByPaymentDateAsc(loan.id)
            .filter { it.id != excludePaymentId }
        val beforeOutstanding = LoanBalanceCalculator.compute(loan, existing, proposedDate)
        val alloc = LoanBalanceCalculator.previewSplit(loan, existing, proposedDate, proposedAmount)
            ?: throw AppException.badRequest("LOAN_PAYMENT_AMOUNT_INVALID")
        return PaymentSplitPreview(
            amount = alloc.amount,
            interestPaid = alloc.interestPaid,
            principalPaid = alloc.principalPaid,
            accruedInterestBefore = beforeOutstanding.accruedInterest,
            principalBefore = beforeOutstanding.principalRemaining,
        )
    }

    @Transactional
    fun upsertSchedule(householdId: UUID, loanId: UUID, request: LoanScheduleRequest, by: User): LoanSchedule {
        validateSchedule(request)
        val loan = loadOwn(householdId, loanId)
        if (loan.status != LoanStatus.active) throw AppException.badRequest("LOAN_NOT_ACTIVE")
        val existing = schedules.findByLoanId(loan.id)
        if (existing == null) {
            val schedule = LoanSchedule(
                loanId = loan.id,
                frequency = request.frequency,
                dayOfWeek = request.dayOfWeek,
                dayOfMonth = request.dayOfMonth,
                expectedAmount = Money.normalize(request.expectedAmount),
                active = request.active,
                createdByUserId = by.id,
                updatedByUserId = by.id,
            )
            return schedules.save(schedule)
        }
        existing.frequency = request.frequency
        existing.dayOfWeek = request.dayOfWeek
        existing.dayOfMonth = request.dayOfMonth
        existing.expectedAmount = Money.normalize(request.expectedAmount)
        existing.active = request.active
        existing.updatedByUserId = by.id
        return existing
    }

    @Transactional
    fun setScheduleActive(householdId: UUID, loanId: UUID, active: Boolean, by: User): LoanSchedule {
        val loan = loadOwn(householdId, loanId)
        val schedule = schedules.findByLoanId(loan.id)
            ?: throw AppException.notFound("LOAN_SCHEDULE_NOT_FOUND")
        schedule.active = active
        schedule.updatedByUserId = by.id
        return schedule
    }

    @Transactional
    fun deleteSchedule(householdId: UUID, loanId: UUID) {
        val loan = loadOwn(householdId, loanId)
        val schedule = schedules.findByLoanId(loan.id) ?: return
        schedules.delete(schedule)
    }

    @Transactional(readOnly = true)
    fun exportLoansCsv(householdId: UUID): String {
        val all = loans.findAllByHouseholdId(householdId)
            .sortedWith(compareBy({ it.startDate }, { it.borrowerName.lowercase() }))
        val sb = StringBuilder()
        sb.append(
            Csv.row(
                "borrower_name", "principal_amount", "start_date", "interest_type",
                "annual_interest_rate", "compounding_period", "description", "status",
            )
        )
        for (l in all) {
            sb.append(
                Csv.row(
                    l.borrowerName,
                    Csv.decimal(l.principalAmount),
                    l.startDate,
                    l.interestType.name,
                    l.annualInterestRate?.let { Csv.decimal(it) }.orEmpty(),
                    l.compoundingPeriod?.name.orEmpty(),
                    l.description.orEmpty(),
                    l.status.name,
                )
            )
        }
        return sb.toString()
    }

    @Transactional(readOnly = true)
    fun exportPaymentsCsv(householdId: UUID): String {
        val householdLoans = loans.findAllByHouseholdId(householdId)
            .associateBy { it.id }
        if (householdLoans.isEmpty()) {
            return Csv.row("borrower_name", "loan_start_date", "payment_date", "amount", "description")
        }
        val all = payments.findAllByLoanIdsOrderByPaymentDateAsc(householdLoans.keys)
            .sortedWith(compareBy({ it.paymentDate }, { it.id }))
        val sb = StringBuilder()
        sb.append(Csv.row("borrower_name", "loan_start_date", "payment_date", "amount", "description"))
        for (p in all) {
            val loan = householdLoans[p.loanId] ?: continue
            sb.append(
                Csv.row(
                    loan.borrowerName,
                    loan.startDate,
                    p.paymentDate,
                    Csv.decimal(p.amount),
                    p.description.orEmpty(),
                )
            )
        }
        return sb.toString()
    }

    private fun transitionStatus(
        householdId: UUID,
        id: UUID,
        target: LoanStatus,
        explicitDate: LocalDate?,
        by: User,
    ): Loan {
        val loan = loadOwn(householdId, id)
        if (loan.status == target) return loan
        val closedDate = explicitDate ?: LocalDate.now()
        if (closedDate.isBefore(loan.startDate)) {
            throw AppException.badRequest("LOAN_CLOSED_DATE_BEFORE_START")
        }
        loan.status = target
        loan.closedDate = closedDate
        loan.updatedByUserId = by.id
        schedules.findByLoanId(loan.id)?.let {
            it.active = false
            it.updatedByUserId = by.id
        }
        return loan
    }

    private fun validateLoan(request: LoanRequest) {
        when (request.interestType) {
            InterestType.none -> {
                if (request.annualInterestRate != null && request.annualInterestRate.signum() != 0) {
                    throw AppException.badRequest("LOAN_RATE_NOT_ALLOWED")
                }
                if (request.compoundingPeriod != null) {
                    throw AppException.badRequest("LOAN_COMPOUNDING_NOT_ALLOWED")
                }
            }
            InterestType.simple -> {
                requirePositiveRate(request.annualInterestRate)
                if (request.compoundingPeriod != null) {
                    throw AppException.badRequest("LOAN_COMPOUNDING_NOT_ALLOWED")
                }
            }
            InterestType.compound -> {
                requirePositiveRate(request.annualInterestRate)
                if (request.compoundingPeriod == null) {
                    throw AppException.badRequest("LOAN_COMPOUNDING_REQUIRED")
                }
            }
        }
        if (request.borrowerName.isBlank()) {
            throw AppException.badRequest("LOAN_BORROWER_REQUIRED")
        }
    }

    private fun requirePositiveRate(rate: BigDecimal?) {
        if (rate == null || rate.signum() <= 0) {
            throw AppException.badRequest("LOAN_RATE_REQUIRED")
        }
    }

    private fun validatePayment(loan: Loan, date: LocalDate, amount: BigDecimal) {
        if (date.isBefore(loan.startDate)) {
            throw AppException.badRequest("LOAN_PAYMENT_BEFORE_START")
        }
        if (loan.status != LoanStatus.active) {
            throw AppException.badRequest("LOAN_NOT_ACTIVE")
        }
        if (amount.signum() <= 0) {
            throw AppException.badRequest("LOAN_PAYMENT_AMOUNT_INVALID")
        }
    }

    private fun validateSchedule(request: LoanScheduleRequest) {
        val ok = when (request.frequency) {
            LoanFrequency.weekly -> request.dayOfWeek != null && request.dayOfWeek in 1..7 && request.dayOfMonth == null
            LoanFrequency.monthly -> request.dayOfMonth != null && request.dayOfMonth in 1..31 && request.dayOfWeek == null
            LoanFrequency.yearly -> request.dayOfMonth != null && request.dayOfMonth in 1..31 && request.dayOfWeek == null
        }
        if (!ok) throw AppException.badRequest("LOAN_SCHEDULE_FIELDS_INVALID")
        if (request.expectedAmount.signum() <= 0) {
            throw AppException.badRequest("LOAN_SCHEDULE_AMOUNT_INVALID")
        }
    }

    private fun normalizeRate(rate: BigDecimal): BigDecimal =
        rate.setScale(4, java.math.RoundingMode.HALF_EVEN)

    private fun loadOwn(householdId: UUID, id: UUID): Loan {
        val loan = loans.findById(id).orElseThrow { AppException.notFound("LOAN_NOT_FOUND") }
        if (loan.householdId != householdId) throw AppException.notFound("LOAN_NOT_FOUND")
        return loan
    }

    private fun loadPayment(loanId: UUID, paymentId: UUID): LoanPayment {
        val payment = payments.findById(paymentId).orElseThrow {
            AppException.notFound("LOAN_PAYMENT_NOT_FOUND")
        }
        if (payment.loanId != loanId) throw AppException.notFound("LOAN_PAYMENT_NOT_FOUND")
        return payment
    }

    private fun toSummary(loan: Loan, outstanding: Outstanding, schedule: LoanSchedule?): LoanSummary =
        LoanSummary(
            id = loan.id,
            borrowerName = loan.borrowerName,
            principalAmount = loan.principalAmount,
            startDate = loan.startDate,
            description = loan.description,
            interestType = loan.interestType,
            annualInterestRate = loan.annualInterestRate,
            compoundingPeriod = loan.compoundingPeriod,
            status = loan.status,
            closedDate = loan.closedDate,
            principalRemaining = outstanding.principalRemaining,
            accruedInterest = outstanding.accruedInterest,
            totalOutstanding = outstanding.totalOutstanding,
            hasSchedule = schedule != null,
            scheduleActive = schedule?.active == true,
        )

    private fun mergePaymentDtos(payments: List<LoanPayment>, outstanding: Outstanding): List<LoanPaymentDto> {
        val byId = outstanding.allocations.associateBy { it.paymentId }
        return payments.map { p ->
            val alloc = byId[p.id]
            LoanPaymentDto(
                id = p.id,
                loanId = p.loanId,
                paymentDate = p.paymentDate,
                amount = p.amount,
                description = p.description,
                scheduleId = p.scheduleId,
                interestPaid = alloc?.interestPaid ?: Money.normalize(BigDecimal.ZERO),
                principalPaid = alloc?.principalPaid ?: Money.normalize(p.amount),
            )
        }
    }
}

fun LoanSchedule.toDto(): LoanScheduleDto = LoanScheduleDto(
    id = id,
    loanId = loanId,
    frequency = frequency,
    dayOfWeek = dayOfWeek,
    dayOfMonth = dayOfMonth,
    expectedAmount = expectedAmount,
    active = active,
    lastMaterializedThrough = lastMaterializedThrough,
)
