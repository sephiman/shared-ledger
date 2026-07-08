package com.sephilabs.sharedledger.networth.amortization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Pure engine tests — no Spring/DB. Validates the four amortization methods, revisions, prepayments. */
class AmortizationCalculatorTest {

    // Start on the charge-day boundary so these method tests exercise full first periods; the
    // partial-first-period (odd-days) proration is covered by its own test below.
    private val start = LocalDate.of(2026, 1, 1)

    private fun terms(
        principal: String,
        rate: String,
        method: AmortizationMethod,
        term: Int? = null,
        instalment: String? = null,
        chargeDay: Int = 1,
    ) = PartTerms(
        principal = BigDecimal(principal),
        annualRate = BigDecimal(rate),
        method = method,
        termMonths = term,
        instalment = instalment?.let { BigDecimal(it) },
        startDate = start,
        chargeDay = chargeDay,
    )

    @Test
    fun `a partial first period prorates the whole instalment`() {
        // Origin on the 15th, charged on the 1st → first period ~half a month (17/31 of a full cycle).
        val t = PartTerms(BigDecimal("100000"), BigDecimal("6"), AmortizationMethod.french, 360, null, LocalDate.of(2026, 1, 15), 1)
        val p = AmortizationCalculator.project(t, emptyList(), emptyList())
        val stub = p.rows.first()  // the partial first charge
        val second = p.rows[1]     // first full month
        // The whole instalment is prorated: interest, principal and total are ~17/31 of a full month.
        assertThat(stub.interest).isLessThan(BigDecimal("300")).isGreaterThan(BigDecimal("250"))   // 500 * 17/31 ≈ 274
        assertThat(stub.principal).isLessThan(BigDecimal("60")).isGreaterThan(BigDecimal("45"))     // 99.55 * 17/31 ≈ 55
        assertThat(stub.instalment).isLessThan(BigDecimal("360")).isGreaterThan(BigDecimal("300"))  // 599.55 * 17/31 ≈ 329
        // The following period is a full instalment again.
        assertThat(second.instalment).isEqualByComparingTo("599.55")
        assertThat(second.interest).isGreaterThan(BigDecimal("490"))
    }

    @Test
    fun `french schedule amortizes to zero with constant instalment`() {
        val p = AmortizationCalculator.project(terms("100000", "6", AmortizationMethod.french, term = 360), emptyList(), emptyList())
        // Monthly rate 0.5%: instalment ~ 599.55, first interest exactly 500.00.
        assertThat(p.rows.first().interest).isEqualByComparingTo("500.00")
        assertThat(p.rows.first().instalment).isEqualByComparingTo("599.55")
        assertThat(p.rows.size).isEqualTo(360)
        assertThat(p.rows.last().balance).isEqualByComparingTo("0.00")
        assertThat(p.payoffDate).isNotNull()
    }

    @Test
    fun `zero-interest splits principal evenly with no interest`() {
        val p = AmortizationCalculator.project(terms("1200", "0", AmortizationMethod.zero, term = 12), emptyList(), emptyList())
        assertThat(p.rows.size).isEqualTo(12)
        assertThat(p.rows.first().interest).isEqualByComparingTo("0.00")
        assertThat(p.rows.first().principal).isEqualByComparingTo("100.00")
        assertThat(p.totalInterest).isEqualByComparingTo("0.00")
        assertThat(p.rows.last().balance).isEqualByComparingTo("0.00")
    }

    @Test
    fun `german keeps constant principal and decreasing instalment`() {
        val p = AmortizationCalculator.project(terms("1200", "12", AmortizationMethod.german, term = 12), emptyList(), emptyList())
        assertThat(p.rows.first().principal).isEqualByComparingTo("100.00")
        assertThat(p.rows.first().interest).isEqualByComparingTo("12.00") // 1200 * 1%
        // Instalment decreases: first > last.
        assertThat(p.rows.first().instalment).isGreaterThan(p.rows.last().instalment)
        assertThat(p.rows.last().balance).isEqualByComparingTo("0.00")
    }

    @Test
    fun `interest-only pays interest each period and principal at the end`() {
        val p = AmortizationCalculator.project(terms("1000", "12", AmortizationMethod.interest_only, term = 12), emptyList(), emptyList())
        assertThat(p.rows.first().interest).isEqualByComparingTo("10.00") // 1000 * 1%
        assertThat(p.rows.first().principal).isEqualByComparingTo("0.00")
        assertThat(p.rows.last().principal).isEqualByComparingTo("1000.00")
        assertThat(p.rows.last().balance).isEqualByComparingTo("0.00")
    }

    @Test
    fun `reduce-term prepayment shortens the schedule and saves interest`() {
        val base = AmortizationCalculator.project(terms("100000", "6", AmortizationMethod.french, term = 360), emptyList(), emptyList())
        val withPrepay = AmortizationCalculator.project(
            terms("100000", "6", AmortizationMethod.french, term = 360),
            emptyList(),
            listOf(PrepaymentInput(LocalDate.of(2026, 3, 1), BigDecimal("20000"), PrepaymentMode.reduce_term)),
        )
        assertThat(withPrepay.rows.size).isLessThan(base.rows.size)
        assertThat(withPrepay.totalInterest).isLessThan(base.totalInterest)
    }

    @Test
    fun `origin mode reconstructs past rows and the current balance has amortized below the original principal`() {
        // Started ~6 years ago from the original principal → full schedule incl. past.
        val origin = LocalDate.now().minusYears(6).withDayOfMonth(10)
        val t = PartTerms(BigDecimal("100000"), BigDecimal("6"), AmortizationMethod.french, 360, null, origin, 1)
        val p = AmortizationCalculator.project(t, emptyList(), emptyList())
        assertThat(p.rows.first().date).isBefore(LocalDate.now()) // past history exists
        assertThat(p.rows.any { it.date.isAfter(LocalDate.now()) }).isTrue() // future too
        val current = AmortizationCalculator.balanceAt(t, emptyList(), emptyList(), LocalDate.now())
        assertThat(current).isLessThan(BigDecimal("100000"))
        assertThat(current).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `re-anchor resets the balance at its date and reprojects from there`() {
        val origin = LocalDate.now().minusYears(6).withDayOfMonth(1)
        val t = PartTerms(BigDecimal("100000"), BigDecimal("6"), AmortizationMethod.french, 360, null, origin, 1)
        val anchorDate = LocalDate.now().withDayOfMonth(1)
        val anchored = AmortizationCalculator.balanceAt(t, emptyList(), emptyList(), anchorDate, AnchorInput(anchorDate, BigDecimal("12345.67")))
        // The anchored balance drives the schedule from that date (one instalment already applied).
        assertThat(anchored).isLessThanOrEqualTo(BigDecimal("12345.67"))
        assertThat(anchored).isGreaterThan(BigDecimal("11000"))
    }

    @Test
    fun `reduce-instalment prepayment lowers the instalment, keeps the payoff, and does not liquidate`() {
        // Instalment-driven part (no explicit term) — the case that used to dump the whole balance.
        val t = PartTerms(BigDecimal("297000"), BigDecimal("1.74"), AmortizationMethod.french, null, BigDecimal("1060.48"), start, 1)
        val base = AmortizationCalculator.project(t, emptyList(), emptyList())
        val prepay = listOf(PrepaymentInput(start.plusMonths(6), BigDecimal("10000"), PrepaymentMode.reduce_instalment))
        val p = AmortizationCalculator.project(t, emptyList(), prepay)

        // The instalment after the prepayment is lower than the original, not the entire balance.
        val after = p.rows.first { it.date.isAfter(start.plusMonths(6)) }
        assertThat(after.instalment).isLessThan(BigDecimal("1060.48"))
        assertThat(after.instalment).isGreaterThan(BigDecimal("500"))
        assertThat(after.principal).isLessThan(BigDecimal("5000")) // NOT the whole ~287k balance
        // Payoff stays essentially the same (reduce term is the mode that shortens it).
        assertThat(p.payoffDate!!).isBetween(base.payoffDate!!.minusMonths(1), base.payoffDate!!.plusMonths(1))
        // Interest is saved versus not prepaying.
        assertThat(p.totalInterest).isLessThan(base.totalInterest)
    }

    @Test
    fun `reduce-instalment on German lowers the fixed principal and keeps the term`() {
        val t = terms("1200", "12", AmortizationMethod.german, term = 12)
        val base = AmortizationCalculator.project(t, emptyList(), emptyList())
        val p = AmortizationCalculator.project(
            t, emptyList(),
            listOf(PrepaymentInput(start.plusMonths(2), BigDecimal("120"), PrepaymentMode.reduce_instalment)),
        )
        assertThat(base.rows.size).isEqualTo(12)
        assertThat(p.rows.size).isEqualTo(12) // term kept, not shortened
        // After the prepayment the constant principal is recomputed lower than the original 100.00.
        val after = p.rows.first { it.date.isAfter(start.plusMonths(2)) }
        assertThat(after.principal).isLessThan(BigDecimal("100"))
        assertThat(p.rows.last().balance).isEqualByComparingTo("0.00")
    }

    @Test
    fun `balanceAt returns the outstanding after the last charge on or before the date`() {
        val t = terms("1200", "0", AmortizationMethod.zero, term = 12)
        // First charge 2026-02-01. After 3 charges (Feb, Mar, Apr) balance = 1200 - 300 = 900.
        val balance = AmortizationCalculator.balanceAt(t, emptyList(), emptyList(), LocalDate.of(2026, 4, 15))
        assertThat(balance).isEqualByComparingTo("900.00")
    }
}
