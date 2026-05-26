package com.sharedledger.loan

import com.sharedledger.common.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class PaymentAllocation(
    val paymentId: java.util.UUID,
    val paymentDate: LocalDate,
    val amount: BigDecimal,
    val interestPaid: BigDecimal,
    val principalPaid: BigDecimal,
)

data class Outstanding(
    val principalRemaining: BigDecimal,
    val accruedInterest: BigDecimal,
    val totalOutstanding: BigDecimal,
    val allocations: List<PaymentAllocation>,
)

/**
 * Computes the outstanding balance of a loan given its terms, the list of payments received,
 * and an "as of" date. Payments apply interest-first, then principal.
 *
 * Algorithm: build a chronologically sorted event list (start, capitalization boundaries when
 * compound, each payment, terminator). Between any two events accrue simple-day-prorated
 * interest on the current principal. At each capitalization event fold accrued interest into
 * principal; at each payment event apply the payment interest-first.
 */
object LoanBalanceCalculator {

    private val MC = MathContext.DECIMAL64
    private val DAYS_PER_YEAR = BigDecimal("365")
    private val ONE_HUNDRED = BigDecimal("100")

    fun compute(loan: Loan, payments: List<LoanPayment>, asOfDate: LocalDate): Outstanding {
        val effectiveEnd = effectiveEnd(loan, asOfDate)
        val livePayments = payments.asSequence()
            .filter { it.deletedAt == null }
            .filter { !it.paymentDate.isBefore(loan.startDate) }
            .filter { !it.paymentDate.isAfter(effectiveEnd) }
            .sortedWith(compareBy({ it.paymentDate }, { it.id }))
            .toList()

        if (loan.interestType == InterestType.none) {
            return computeNoInterest(loan, livePayments)
        }

        val rate = loan.annualInterestRate
            ?.divide(ONE_HUNDRED, MC)
            ?: BigDecimal.ZERO

        val events = buildEvents(loan, livePayments, effectiveEnd)

        var principal = loan.principalAmount
        var accrued: BigDecimal = BigDecimal.ZERO
        var cursor = loan.startDate
        val allocations = mutableListOf<PaymentAllocation>()

        for (event in events) {
            if (event.date.isAfter(cursor)) {
                val days = ChronoUnit.DAYS.between(cursor, event.date).toBigDecimal()
                if (days > BigDecimal.ZERO && rate > BigDecimal.ZERO) {
                    val interestSegment = principal
                        .multiply(rate, MC)
                        .multiply(days, MC)
                        .divide(DAYS_PER_YEAR, MC)
                    accrued = accrued.add(interestSegment, MC)
                }
                cursor = event.date
            }
            when (event) {
                is Event.Capitalize -> {
                    principal = principal.add(accrued, MC)
                    accrued = BigDecimal.ZERO
                }
                is Event.Payment -> {
                    val paymentAmount = event.payment.amount
                    val interestPaid = paymentAmount.min(accrued.setScale(Money.SCALE, RoundingMode.HALF_EVEN))
                        .coerceAtLeast(BigDecimal.ZERO)
                    val remainingForPrincipal = paymentAmount.subtract(interestPaid, MC)
                    val principalPaid = remainingForPrincipal.min(principal.setScale(Money.SCALE, RoundingMode.HALF_EVEN))
                        .coerceAtLeast(BigDecimal.ZERO)
                    accrued = accrued.subtract(interestPaid, MC).coerceAtLeast(BigDecimal.ZERO)
                    principal = principal.subtract(principalPaid, MC).coerceAtLeast(BigDecimal.ZERO)
                    allocations += PaymentAllocation(
                        paymentId = event.payment.id,
                        paymentDate = event.payment.paymentDate,
                        amount = Money.normalize(paymentAmount),
                        interestPaid = Money.normalize(interestPaid),
                        principalPaid = Money.normalize(principalPaid),
                    )
                }
                is Event.Terminator -> Unit
            }
        }

        val principalOut = principal.setScale(Money.SCALE, RoundingMode.HALF_EVEN).coerceAtLeast(BigDecimal.ZERO)
        val accruedOut = accrued.setScale(Money.SCALE, RoundingMode.HALF_EVEN).coerceAtLeast(BigDecimal.ZERO)
        return Outstanding(
            principalRemaining = principalOut,
            accruedInterest = accruedOut,
            totalOutstanding = principalOut.add(accruedOut),
            allocations = allocations,
        )
    }

    /**
     * Preview-only: project what the split would look like if a payment of [proposedAmount]
     * landed at [proposedDate] in addition to [existingPayments]. Returns null if proposedAmount
     * is non-positive.
     */
    fun previewSplit(
        loan: Loan,
        existingPayments: List<LoanPayment>,
        proposedDate: LocalDate,
        proposedAmount: BigDecimal,
    ): PaymentAllocation? {
        if (proposedAmount <= BigDecimal.ZERO) return null
        val syntheticId = java.util.UUID(0L, 0L)
        val synthetic = LoanPayment(
            id = syntheticId,
            loanId = loan.id,
            paymentDate = proposedDate,
            amount = proposedAmount,
            createdByUserId = syntheticId,
            updatedByUserId = syntheticId,
        )
        val combined = existingPayments + synthetic
        val out = compute(loan, combined, proposedDate)
        return out.allocations.firstOrNull { it.paymentId == syntheticId }
    }

    private fun computeNoInterest(loan: Loan, payments: List<LoanPayment>): Outstanding {
        var principal = loan.principalAmount
        val allocations = mutableListOf<PaymentAllocation>()
        for (p in payments) {
            val principalPaid = p.amount.min(principal).coerceAtLeast(BigDecimal.ZERO)
            principal = principal.subtract(principalPaid).coerceAtLeast(BigDecimal.ZERO)
            allocations += PaymentAllocation(
                paymentId = p.id,
                paymentDate = p.paymentDate,
                amount = Money.normalize(p.amount),
                interestPaid = Money.normalize(BigDecimal.ZERO),
                principalPaid = Money.normalize(principalPaid),
            )
        }
        val principalOut = Money.normalize(principal)
        return Outstanding(
            principalRemaining = principalOut,
            accruedInterest = Money.normalize(BigDecimal.ZERO),
            totalOutstanding = principalOut,
            allocations = allocations,
        )
    }

    private fun effectiveEnd(loan: Loan, asOfDate: LocalDate): LocalDate {
        val closed = loan.closedDate
        return if (closed != null && closed.isBefore(asOfDate)) closed else asOfDate
    }

    private fun buildEvents(loan: Loan, payments: List<LoanPayment>, end: LocalDate): List<Event> {
        val list = mutableListOf<Event>()
        if (loan.interestType == InterestType.compound) {
            val period = loan.compoundingPeriod ?: CompoundingPeriod.monthly
            var anchor = nextCapAfter(loan.startDate, period)
            while (!anchor.isAfter(end)) {
                list += Event.Capitalize(anchor)
                anchor = nextCapAfter(anchor, period)
            }
        }
        for (p in payments) list += Event.Payment(p)
        list += Event.Terminator(end)
        // Capitalization MUST resolve before a payment on the same date, so the payment
        // applies after that period's interest has been folded into principal. Payments on
        // the same date keep input order via sortedWith(comparator) above.
        return list.sortedWith(
            compareBy(
                { it.date },
                { eventOrder(it) },
            )
        )
    }

    private fun eventOrder(e: Event): Int = when (e) {
        is Event.Capitalize -> 0
        is Event.Payment -> 1
        is Event.Terminator -> 2
    }

    private fun nextCapAfter(date: LocalDate, period: CompoundingPeriod): LocalDate = when (period) {
        CompoundingPeriod.monthly -> date.plusMonths(1)
        CompoundingPeriod.yearly -> date.plusYears(1)
    }

    private sealed class Event {
        abstract val date: LocalDate
        data class Capitalize(override val date: LocalDate) : Event()
        data class Payment(val payment: LoanPayment) : Event() {
            override val date: LocalDate get() = payment.paymentDate
        }
        data class Terminator(override val date: LocalDate) : Event()
    }
}
