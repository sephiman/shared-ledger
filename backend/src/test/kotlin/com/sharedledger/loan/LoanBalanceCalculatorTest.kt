package com.sharedledger.loan

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class LoanBalanceCalculatorTest {

    private fun loan(
        type: InterestType,
        rate: String? = null,
        compounding: CompoundingPeriod? = null,
        principal: String = "1000.00",
        start: LocalDate = LocalDate.of(2025, 1, 1),
        status: LoanStatus = LoanStatus.active,
        closed: LocalDate? = null,
    ) = Loan(
        householdId = UUID.randomUUID(),
        borrowerName = "Alice",
        principalAmount = BigDecimal(principal),
        startDate = start,
        interestType = type,
        annualInterestRate = rate?.let { BigDecimal(it) },
        compoundingPeriod = compounding,
        status = status,
        closedDate = closed,
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )

    private fun payment(
        loan: Loan,
        date: LocalDate,
        amount: String,
        id: UUID = UUID.randomUUID(),
    ) = LoanPayment(
        id = id,
        loanId = loan.id,
        paymentDate = date,
        amount = BigDecimal(amount),
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )

    @Test
    fun `no interest, no payments leaves principal intact`() {
        val l = loan(InterestType.none)
        val out = LoanBalanceCalculator.compute(l, emptyList(), LocalDate.of(2025, 6, 1))
        assertThat(out.principalRemaining).isEqualByComparingTo("1000.00")
        assertThat(out.accruedInterest).isEqualByComparingTo("0.00")
        assertThat(out.totalOutstanding).isEqualByComparingTo("1000.00")
    }

    @Test
    fun `no interest, payments reduce principal directly`() {
        val l = loan(InterestType.none)
        val payments = listOf(
            payment(l, LocalDate.of(2025, 2, 1), "300.00"),
            payment(l, LocalDate.of(2025, 3, 1), "200.00"),
        )
        val out = LoanBalanceCalculator.compute(l, payments, LocalDate.of(2025, 4, 1))
        assertThat(out.principalRemaining).isEqualByComparingTo("500.00")
        assertThat(out.allocations).hasSize(2)
        assertThat(out.allocations[0].principalPaid).isEqualByComparingTo("300.00")
        assertThat(out.allocations[0].interestPaid).isEqualByComparingTo("0.00")
    }

    @Test
    fun `simple interest accrues across full year without payments`() {
        // 10% simple of 1000 for 365 days = 100
        val l = loan(InterestType.simple, rate = "10")
        val out = LoanBalanceCalculator.compute(l, emptyList(), LocalDate.of(2026, 1, 1))
        assertThat(out.principalRemaining).isEqualByComparingTo("1000.00")
        assertThat(out.accruedInterest).isEqualByComparingTo("100.00")
    }

    @Test
    fun `simple interest, payment covers accrued and reduces principal`() {
        // 10% on 1000 for 31 days = ~8.49 (1000 * 0.10 * 31/365 ≈ 8.4932)
        val l = loan(InterestType.simple, rate = "10")
        val payments = listOf(payment(l, LocalDate.of(2025, 2, 1), "108.49"))
        val out = LoanBalanceCalculator.compute(l, payments, LocalDate.of(2025, 2, 1))
        val alloc = out.allocations.single()
        assertThat(alloc.interestPaid).isBetween(BigDecimal("8.48"), BigDecimal("8.50"))
        assertThat(alloc.principalPaid).isBetween(BigDecimal("99.99"), BigDecimal("100.01"))
        assertThat(out.accruedInterest).isEqualByComparingTo("0.00")
    }

    @Test
    fun `payment smaller than accrued interest does not reduce principal`() {
        // Over a year: 100 accrued. Pay only 50: principal stays at 1000, 50 interest carries forward.
        val l = loan(InterestType.simple, rate = "10")
        val payments = listOf(payment(l, LocalDate.of(2026, 1, 1), "50.00"))
        val out = LoanBalanceCalculator.compute(l, payments, LocalDate.of(2026, 1, 1))
        val alloc = out.allocations.single()
        assertThat(alloc.interestPaid).isEqualByComparingTo("50.00")
        assertThat(alloc.principalPaid).isEqualByComparingTo("0.00")
        assertThat(out.principalRemaining).isEqualByComparingTo("1000.00")
        assertThat(out.accruedInterest).isEqualByComparingTo("50.00")
    }

    @Test
    fun `overpayment caps at total outstanding`() {
        val l = loan(InterestType.simple, rate = "10")
        val payments = listOf(payment(l, LocalDate.of(2026, 1, 1), "5000.00"))
        val out = LoanBalanceCalculator.compute(l, payments, LocalDate.of(2026, 1, 1))
        assertThat(out.principalRemaining).isEqualByComparingTo("0.00")
        assertThat(out.accruedInterest).isEqualByComparingTo("0.00")
    }

    @Test
    fun `compound monthly capitalizes each month`() {
        // 12% nominal, monthly compounding. With no payments and using daily simple-interest
        // segments between monthly capitalizations, the effective month rate ≈ 12%/12 = 1%,
        // so 1000 → ~1126.83 after 12 months. We assert it sits in a tight band that the
        // monthly compounding produces (anywhere 1126.00-1128.00).
        val l = loan(InterestType.compound, rate = "12", compounding = CompoundingPeriod.monthly)
        val out = LoanBalanceCalculator.compute(l, emptyList(), LocalDate.of(2026, 1, 1))
        assertThat(out.totalOutstanding).isBetween(BigDecimal("1126.00"), BigDecimal("1128.00"))
    }

    @Test
    fun `compound yearly capitalizes once per year`() {
        // 10% yearly compound: 1000 → 1100 after one year.
        val l = loan(InterestType.compound, rate = "10", compounding = CompoundingPeriod.yearly)
        val out = LoanBalanceCalculator.compute(l, emptyList(), LocalDate.of(2026, 1, 1))
        assertThat(out.totalOutstanding).isBetween(BigDecimal("1099.00"), BigDecimal("1101.00"))
    }

    @Test
    fun `closed_date freezes interest`() {
        val l = loan(
            InterestType.simple,
            rate = "10",
            status = LoanStatus.settled,
            closed = LocalDate.of(2025, 7, 1),
        )
        val outAtClose = LoanBalanceCalculator.compute(l, emptyList(), LocalDate.of(2025, 7, 1))
        val outLater = LoanBalanceCalculator.compute(l, emptyList(), LocalDate.of(2027, 1, 1))
        assertThat(outAtClose.accruedInterest).isEqualByComparingTo(outLater.accruedInterest)
    }

    @Test
    fun `soft-deleted payments are excluded`() {
        val l = loan(InterestType.none)
        val live = payment(l, LocalDate.of(2025, 2, 1), "200.00")
        val deleted = payment(l, LocalDate.of(2025, 3, 1), "500.00").apply { deletedAt = java.time.Instant.now() }
        val out = LoanBalanceCalculator.compute(l, listOf(live, deleted), LocalDate.of(2025, 4, 1))
        assertThat(out.principalRemaining).isEqualByComparingTo("800.00")
    }

    @Test
    fun `previewSplit returns interest-first allocation`() {
        val l = loan(InterestType.simple, rate = "10")
        // After 1 year of accrual (~100 interest), preview a 150 payment.
        val split = LoanBalanceCalculator.previewSplit(l, emptyList(), LocalDate.of(2026, 1, 1), BigDecimal("150.00"))
        assertThat(split).isNotNull
        assertThat(split!!.interestPaid).isEqualByComparingTo("100.00")
        assertThat(split.principalPaid).isEqualByComparingTo("50.00")
    }
}
