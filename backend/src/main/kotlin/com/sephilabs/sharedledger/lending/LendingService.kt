package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.notification.NotifyAction
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class LendingService(
    private val lendings: LendingRepository,
    private val payments: LendingPaymentRepository,
    private val schedules: LendingScheduleRepository,
    private val notifications: NotificationPublisher,
) {

    @Transactional
    fun create(householdId: UUID, request: LendingRequest, by: User): Lending {
        validateLending(request)
        val lending = Lending(
            householdId = householdId,
            borrowerName = request.borrowerName.trim(),
            principalAmount = Money.normalize(request.principalAmount),
            startDate = request.startDate,
            description = request.description?.takeIf { it.isNotBlank() },
            interestType = request.interestType,
            annualInterestRate = request.annualInterestRate?.let { normalizeRate(it) },
            compoundingPeriod = request.compoundingPeriod,
            status = LendingStatus.active,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        return lendings.save(lending)
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: LendingRequest, by: User): Lending {
        validateLending(request)
        val lending = loadOwn(householdId, id)
        if (lending.status != LendingStatus.active) {
            throw AppException.badRequest("LENDING_NOT_ACTIVE")
        }
        lending.borrowerName = request.borrowerName.trim()
        lending.principalAmount = Money.normalize(request.principalAmount)
        lending.startDate = request.startDate
        lending.description = request.description?.takeIf { it.isNotBlank() }
        lending.interestType = request.interestType
        lending.annualInterestRate = request.annualInterestRate?.let { normalizeRate(it) }
        lending.compoundingPeriod = request.compoundingPeriod
        lending.updatedByUserId = by.id
        return lending
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val lending = loadOwn(householdId, id)
        lending.deletedAt = Instant.now()
        lending.updatedByUserId = by.id
    }

    @Transactional
    fun settle(householdId: UUID, id: UUID, request: LendingStatusTransitionRequest, by: User): Lending =
        transitionStatus(householdId, id, LendingStatus.settled, request.closedDate, by)

    @Transactional
    fun writeOff(householdId: UUID, id: UUID, request: LendingStatusTransitionRequest, by: User): Lending =
        transitionStatus(householdId, id, LendingStatus.written_off, request.closedDate, by)

    @Transactional
    fun reopen(householdId: UUID, id: UUID, by: User): Lending {
        val lending = loadOwn(householdId, id)
        if (lending.status == LendingStatus.active) return lending
        lending.status = LendingStatus.active
        lending.closedDate = null
        lending.updatedByUserId = by.id
        return lending
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): LendingDetail {
        val lending = loadOwn(householdId, id)
        val lendingPayments = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id)
        val schedule = schedules.findByLendingId(lending.id)
        val outstanding = LendingBalanceCalculator.compute(lending, lendingPayments, LocalDate.now())
        return LendingDetail(
            summary = toSummary(lending, outstanding, schedule),
            payments = mergePaymentDtos(lendingPayments, outstanding),
            schedule = schedule?.toDto(),
        )
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, status: String?, top: Int?): LendingListResponse {
        val all = when (status?.lowercase()) {
            "active" -> lendings.findAllByHouseholdIdAndStatus(householdId, LendingStatus.active)
            "settled" -> lendings.findAllByHouseholdIdAndStatus(householdId, LendingStatus.settled)
            "written_off" -> lendings.findAllByHouseholdIdAndStatus(householdId, LendingStatus.written_off)
            null, "", "all" -> lendings.findAllByHouseholdId(householdId)
            else -> throw AppException.badRequest("LENDING_STATUS_INVALID")
        }
        if (all.isEmpty()) {
            return LendingListResponse(
                lendings = emptyList(),
                totalOutstandingActive = BigDecimal.ZERO,
                activeCount = 0,
                top = emptyList(),
            )
        }
        val ids = all.map { it.id }
        val paymentsByLending = payments.findAllByLendingIdsOrderByPaymentDateAsc(ids).groupBy { it.lendingId }
        val scheduleByLending = schedules.findAll()
            .filter { it.lendingId in ids.toHashSet() }
            .associateBy { it.lendingId }
        val now = LocalDate.now()
        val summaries = all.map { lending ->
            val outstanding = LendingBalanceCalculator.compute(
                lending,
                paymentsByLending[lending.id].orEmpty(),
                now,
            )
            toSummary(lending, outstanding, scheduleByLending[lending.id])
        }.sortedWith(
            compareByDescending<LendingSummary> { it.status == LendingStatus.active }
                .thenByDescending { it.totalOutstanding }
                .thenBy { it.borrowerName.lowercase() }
        )
        val active = summaries.filter { it.status == LendingStatus.active }
        val total = active.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.totalOutstanding) }
        val topN = (top ?: 0).coerceAtLeast(0)
        val topList = if (topN == 0) emptyList() else active.take(topN)
        return LendingListResponse(
            lendings = summaries,
            totalOutstandingActive = Money.normalize(total),
            activeCount = active.size,
            top = topList,
        )
    }

    @Transactional
    fun registerPayment(householdId: UUID, lendingId: UUID, request: LendingPaymentRequest, by: User): LendingPayment {
        val lending = loadOwn(householdId, lendingId)
        validatePayment(lending, request.paymentDate, request.amount)
        val payment = LendingPayment(
            lendingId = lending.id,
            paymentDate = request.paymentDate,
            amount = Money.normalize(request.amount),
            description = request.description?.takeIf { it.isNotBlank() },
            scheduleId = null,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        payments.save(payment)
        settleIfPaidOff(lending, by)
        notifications.lendingPayment(payment, lending.householdId, lending.borrowerName, NotifyAction.CREATE, NotifyActor.Human(by.email))
        return payment
    }

    @Transactional
    fun updatePayment(
        householdId: UUID,
        lendingId: UUID,
        paymentId: UUID,
        request: LendingPaymentRequest,
        by: User,
    ): LendingPayment {
        val lending = loadOwn(householdId, lendingId)
        val payment = loadPayment(lending.id, paymentId)
        validatePayment(lending, request.paymentDate, request.amount)
        payment.paymentDate = request.paymentDate
        payment.amount = Money.normalize(request.amount)
        payment.description = request.description?.takeIf { it.isNotBlank() }
        payment.updatedByUserId = by.id
        settleIfPaidOff(lending, by)
        notifications.lendingPayment(payment, lending.householdId, lending.borrowerName, NotifyAction.UPDATE, NotifyActor.Human(by.email))
        return payment
    }

    @Transactional
    fun deletePayment(householdId: UUID, lendingId: UUID, paymentId: UUID, by: User) {
        val lending = loadOwn(householdId, lendingId)
        val payment = loadPayment(lending.id, paymentId)
        payment.deletedAt = Instant.now()
        payment.updatedByUserId = by.id
        notifications.lendingPayment(payment, lending.householdId, lending.borrowerName, NotifyAction.DELETE, NotifyActor.Human(by.email))
    }

    @Transactional
    fun upsertSchedule(householdId: UUID, lendingId: UUID, request: LendingScheduleRequest, by: User): LendingSchedule {
        validateSchedule(request)
        val lending = loadOwn(householdId, lendingId)
        if (lending.status != LendingStatus.active) throw AppException.badRequest("LENDING_NOT_ACTIVE")
        val existing = schedules.findByLendingId(lending.id)
        if (existing == null) {
            val schedule = LendingSchedule(
                lendingId = lending.id,
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
    fun setScheduleActive(householdId: UUID, lendingId: UUID, active: Boolean, by: User): LendingSchedule {
        val lending = loadOwn(householdId, lendingId)
        val schedule = schedules.findByLendingId(lending.id)
            ?: throw AppException.notFound("LENDING_SCHEDULE_NOT_FOUND")
        schedule.active = active
        schedule.updatedByUserId = by.id
        return schedule
    }

    @Transactional
    fun deleteSchedule(householdId: UUID, lendingId: UUID) {
        val lending = loadOwn(householdId, lendingId)
        val schedule = schedules.findByLendingId(lending.id) ?: return
        schedules.delete(schedule)
    }

    @Transactional(readOnly = true)
    fun exportLendingsCsv(householdId: UUID): String {
        val all = lendings.findAllByHouseholdId(householdId)
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
        val householdLendings = lendings.findAllByHouseholdId(householdId)
            .associateBy { it.id }
        if (householdLendings.isEmpty()) {
            return Csv.row("borrower_name", "lending_start_date", "payment_date", "amount", "description")
        }
        val all = payments.findAllByLendingIdsOrderByPaymentDateAsc(householdLendings.keys)
            .sortedWith(compareBy({ it.paymentDate }, { it.id }))
        val sb = StringBuilder()
        sb.append(Csv.row("borrower_name", "lending_start_date", "payment_date", "amount", "description"))
        for (p in all) {
            val lending = householdLendings[p.lendingId] ?: continue
            sb.append(
                Csv.row(
                    lending.borrowerName,
                    lending.startDate,
                    p.paymentDate,
                    Csv.decimal(p.amount),
                    p.description.orEmpty(),
                )
            )
        }
        return sb.toString()
    }

    /** Settles the lending when its balance reaches zero after a payment write. Evaluated as of the latest
     *  payment date, not today, so future-dated payments fall inside the calculator's window; once principal
     *  hits zero no further interest accrues, so a zero balance at that date is final. */
    private fun settleIfPaidOff(lending: Lending, by: User) {
        val livePayments = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id)
        val lastPaymentDate = livePayments.maxOfOrNull { it.paymentDate } ?: return
        val asOf = maxOf(lastPaymentDate, LocalDate.now())
        val outstanding = LendingBalanceCalculator.compute(lending, livePayments, asOf)
        if (outstanding.totalOutstanding.signum() == 0) {
            transitionStatus(lending.householdId, lending.id, LendingStatus.settled, lastPaymentDate, by)
        }
    }

    private fun transitionStatus(
        householdId: UUID,
        id: UUID,
        target: LendingStatus,
        explicitDate: LocalDate?,
        by: User,
    ): Lending {
        val lending = loadOwn(householdId, id)
        if (lending.status == target) return lending
        val closedDate = explicitDate ?: LocalDate.now()
        if (closedDate.isBefore(lending.startDate)) {
            throw AppException.badRequest("LENDING_CLOSED_DATE_BEFORE_START")
        }
        lending.status = target
        lending.closedDate = closedDate
        lending.updatedByUserId = by.id
        schedules.findByLendingId(lending.id)?.let {
            it.active = false
            it.updatedByUserId = by.id
        }
        return lending
    }

    private fun validateLending(request: LendingRequest) {
        when (request.interestType) {
            InterestType.none -> {
                if (request.annualInterestRate != null && request.annualInterestRate.signum() != 0) {
                    throw AppException.badRequest("LENDING_RATE_NOT_ALLOWED")
                }
                if (request.compoundingPeriod != null) {
                    throw AppException.badRequest("LENDING_COMPOUNDING_NOT_ALLOWED")
                }
            }
            InterestType.simple -> {
                requirePositiveRate(request.annualInterestRate)
                if (request.compoundingPeriod != null) {
                    throw AppException.badRequest("LENDING_COMPOUNDING_NOT_ALLOWED")
                }
            }
            InterestType.compound -> {
                requirePositiveRate(request.annualInterestRate)
                if (request.compoundingPeriod == null) {
                    throw AppException.badRequest("LENDING_COMPOUNDING_REQUIRED")
                }
            }
        }
        if (request.borrowerName.isBlank()) {
            throw AppException.badRequest("LENDING_BORROWER_REQUIRED")
        }
    }

    private fun requirePositiveRate(rate: BigDecimal?) {
        if (rate == null || rate.signum() <= 0) {
            throw AppException.badRequest("LENDING_RATE_REQUIRED")
        }
    }

    private fun validatePayment(lending: Lending, date: LocalDate, amount: BigDecimal) {
        if (date.isBefore(lending.startDate)) {
            throw AppException.badRequest("LENDING_PAYMENT_BEFORE_START")
        }
        if (lending.status != LendingStatus.active) {
            throw AppException.badRequest("LENDING_NOT_ACTIVE")
        }
        if (amount.signum() <= 0) {
            throw AppException.badRequest("LENDING_PAYMENT_AMOUNT_INVALID")
        }
    }

    private fun validateSchedule(request: LendingScheduleRequest) {
        val ok = when (request.frequency) {
            LendingFrequency.weekly -> request.dayOfWeek != null && request.dayOfWeek in 1..7 && request.dayOfMonth == null
            LendingFrequency.monthly -> request.dayOfMonth != null && request.dayOfMonth in 1..31 && request.dayOfWeek == null
            LendingFrequency.yearly -> request.dayOfMonth != null && request.dayOfMonth in 1..31 && request.dayOfWeek == null
        }
        if (!ok) throw AppException.badRequest("LENDING_SCHEDULE_FIELDS_INVALID")
        if (request.expectedAmount.signum() <= 0) {
            throw AppException.badRequest("LENDING_SCHEDULE_AMOUNT_INVALID")
        }
    }

    private fun normalizeRate(rate: BigDecimal): BigDecimal =
        rate.setScale(4, java.math.RoundingMode.HALF_EVEN)

    private fun loadOwn(householdId: UUID, id: UUID): Lending {
        val lending = lendings.findById(id).orElseThrow { AppException.notFound("LENDING_NOT_FOUND") }
        if (lending.householdId != householdId) throw AppException.notFound("LENDING_NOT_FOUND")
        return lending
    }

    private fun loadPayment(lendingId: UUID, paymentId: UUID): LendingPayment {
        val payment = payments.findById(paymentId).orElseThrow {
            AppException.notFound("LENDING_PAYMENT_NOT_FOUND")
        }
        if (payment.lendingId != lendingId) throw AppException.notFound("LENDING_PAYMENT_NOT_FOUND")
        return payment
    }

    private fun toSummary(lending: Lending, outstanding: Outstanding, schedule: LendingSchedule?): LendingSummary =
        LendingSummary(
            id = lending.id,
            borrowerName = lending.borrowerName,
            principalAmount = lending.principalAmount,
            startDate = lending.startDate,
            description = lending.description,
            interestType = lending.interestType,
            annualInterestRate = lending.annualInterestRate,
            compoundingPeriod = lending.compoundingPeriod,
            status = lending.status,
            closedDate = lending.closedDate,
            principalRemaining = outstanding.principalRemaining,
            accruedInterest = outstanding.accruedInterest,
            totalOutstanding = outstanding.totalOutstanding,
            hasSchedule = schedule != null,
            scheduleActive = schedule?.active == true,
        )

    private fun mergePaymentDtos(payments: List<LendingPayment>, outstanding: Outstanding): List<LendingPaymentDto> {
        val byId = outstanding.allocations.associateBy { it.paymentId }
        return payments.map { p ->
            val alloc = byId[p.id]
            LendingPaymentDto(
                id = p.id,
                lendingId = p.lendingId,
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

fun LendingSchedule.toDto(): LendingScheduleDto = LendingScheduleDto(
    id = id,
    lendingId = lendingId,
    frequency = frequency,
    dayOfWeek = dayOfWeek,
    dayOfMonth = dayOfMonth,
    expectedAmount = expectedAmount,
    active = active,
    lastMaterializedThrough = lastMaterializedThrough,
)
