package com.sephilabs.sharedledger.networth.amortization

import com.sephilabs.sharedledger.common.Money
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Terms of one part, decoupled from JPA so the engine is pure and unit-testable. */
data class PartTerms(
    val principal: BigDecimal,
    val annualRate: BigDecimal, // percent, e.g. 3.5
    val method: AmortizationMethod,
    val termMonths: Int?,
    val instalment: BigDecimal?, // French only; derived from term when absent
    val startDate: LocalDate,
    val chargeDay: Int,
)

data class RevisionInput(val effectiveDate: LocalDate, val annualRate: BigDecimal)
data class PrepaymentInput(val date: LocalDate, val amount: BigDecimal, val mode: PrepaymentMode)

/** Re-anchor: at [date] the outstanding balance really is [balance]; the schedule reprojects from there. */
data class AnchorInput(val date: LocalDate, val balance: BigDecimal)

/** One charged period in the projected schedule. */
data class ScheduleRow(
    val date: LocalDate,
    val interest: BigDecimal,
    val principal: BigDecimal,
    val balance: BigDecimal,
    val instalment: BigDecimal,
)

data class Projection(
    val rows: List<ScheduleRow>,
    val totalInterest: BigDecimal,
    val payoffDate: LocalDate?,
)

/**
 * Forward amortization engine. Modelled on the loans-out [com.sephilabs.sharedledger.loan.LoanBalanceCalculator]
 * (event iteration, interest/principal split, Money rounding) but on clean MONTHLY periods rather than
 * day-prorated accrual, which is what a French/German mortgage schedule needs. It starts from the current
 * state ([PartTerms.principal] at [PartTerms.startDate]) and projects forward; the past is never reconstructed.
 *
 * Rate revisions recompute the instalment on the remaining balance and remaining term (variable-rate,
 * reduce-instalment behaviour). Prepayments reduce the balance at their date, then either keep the
 * instalment (reduce_term) or recompute it (reduce_instalment).
 */
object AmortizationCalculator {

    private val MC = MathContext.DECIMAL64
    private val TWELVE = BigDecimal("12")
    private val HUNDRED = BigDecimal("100")
    private const val MAX_PERIODS = 1200 // 100 years — guards against a non-amortizing configuration

    fun project(
        terms: PartTerms,
        revisions: List<RevisionInput>,
        prepayments: List<PrepaymentInput>,
        horizon: LocalDate? = null,
        anchor: AnchorInput? = null,
    ): Projection {
        val rows = mutableListOf<ScheduleRow>()
        var balance = Money.normalize(terms.principal)
        var annualRate = terms.annualRate
        val sortedRevisions = revisions.sortedBy { it.effectiveDate }
        val sortedPrepayments = prepayments.sortedBy { it.date }
        var revisionIdx = 0
        var prepaymentIdx = 0
        var anchorApplied = anchor == null

        val term = terms.termMonths
        // For an instalment-driven French part (no explicit term), derive the term from the instalment
        // so remainingTerm is meaningful — otherwise a reduce-instalment prepayment or a rate revision
        // would recompute over n=1 and dump the whole balance into the next payment.
        var remainingTerm = term
            ?: terms.instalment?.let { frenchPeriods(terms.principal, monthlyRate(terms.annualRate), it) }
            ?: Int.MAX_VALUE
        // Fixed per-period principal for the linear methods (German, Zero): balance spread over the term.
        // A reduce-instalment prepayment recomputes it over the remaining term (below), which is how those
        // methods lower their instalment while keeping the term.
        var linearPrincipal = if (term != null && term > 0) terms.principal.divide(BigDecimal(term), MC) else BigDecimal.ZERO

        var instalment = resolveInstalment(terms.method, balance, monthlyRate(annualRate), remainingTerm, terms.instalment)
        var totalInterest = BigDecimal.ZERO
        var payoffDate: LocalDate? = null

        var chargeDate = firstChargeDate(terms.startDate, terms.chargeDay)
        // The first charge covers only from the start date to the first charge day, which may be a
        // partial month. That first instalment (interest AND principal) is prorated by the fraction of
        // the cycle the loan actually existed (a start on the cycle boundary → fraction 1 → full month).
        val firstFraction = firstPeriodFraction(terms.startDate, chargeDate)
        var firstPeriod = true
        var periods = 0
        while (balance > MONEY_ZERO && periods < MAX_PERIODS) {
            if (horizon != null && chargeDate.isAfter(horizon)) break

            // Re-anchor: at/after the anchor date the balance is reset to the real figure, correcting
            // any drift; the past (before the anchor) keeps its computed history.
            if (!anchorApplied && anchor != null && !chargeDate.isBefore(anchor.date)) {
                balance = Money.normalize(anchor.balance)
                anchorApplied = true
                instalment = resolveInstalment(terms.method, balance, monthlyRate(annualRate), remainingTerm, terms.instalment)
                if (balance <= MONEY_ZERO) { payoffDate = chargeDate; break }
            }

            // Apply any prepayments effective on or before this charge date.
            var prepaid = false
            while (prepaymentIdx < sortedPrepayments.size && !sortedPrepayments[prepaymentIdx].date.isAfter(chargeDate)) {
                val pp = sortedPrepayments[prepaymentIdx]
                balance = Money.normalize(balance.subtract(pp.amount, MC)).coerceAtLeast(MONEY_ZERO)
                prepaid = pp.mode == PrepaymentMode.reduce_instalment || prepaid
                prepaymentIdx++
            }
            if (balance <= MONEY_ZERO) { payoffDate = chargeDate; break }

            // Apply any rate revisions effective on or before this charge date (recompute instalment).
            var revised = false
            while (revisionIdx < sortedRevisions.size && !sortedRevisions[revisionIdx].effectiveDate.isAfter(chargeDate)) {
                annualRate = sortedRevisions[revisionIdx].annualRate
                revised = true
                revisionIdx++
            }
            if (revised || prepaid) {
                instalment = resolveInstalment(terms.method, balance, monthlyRate(annualRate), remainingTerm, null)
            }
            // Reduce-instalment on a linear method (German/Zero): recompute the fixed principal over the
            // remaining term so the instalment drops while the payoff date is kept.
            if (prepaid && remainingTerm in 1 until Int.MAX_VALUE &&
                (terms.method == AmortizationMethod.german || terms.method == AmortizationMethod.zero)
            ) {
                linearPrincipal = balance.divide(BigDecimal(remainingTerm), MC)
            }

            val monthly = monthlyRate(annualRate)
            val fullInterest = if (terms.method == AmortizationMethod.zero) MONEY_ZERO else Money.normalize(balance.multiply(monthly, MC))
            val isFinalByTerm = remainingTerm <= 1
            val fullPrincipal = when (terms.method) {
                AmortizationMethod.french -> instalment.subtract(fullInterest, MC)
                AmortizationMethod.german -> linearPrincipal
                AmortizationMethod.zero -> if (term != null && term > 0) linearPrincipal else balance
                AmortizationMethod.interest_only -> if (isFinalByTerm) balance else MONEY_ZERO
            }
            // The first (possibly partial) period prorates the WHOLE instalment — interest AND principal
            // alike — so a mid-cycle start pays a fraction of a normal instalment (e.g. ~half).
            val interest = if (firstPeriod) Money.normalize(fullInterest.multiply(firstFraction, MC)) else fullInterest
            var principalComp = Money.normalize(if (firstPeriod) fullPrincipal.multiply(firstFraction, MC) else fullPrincipal)
            // Never pay more principal than remains; the last period settles the balance exactly.
            if (principalComp > balance || (isFinalByTerm && terms.method != AmortizationMethod.interest_only)) {
                principalComp = balance
            }
            if (principalComp < MONEY_ZERO) principalComp = MONEY_ZERO

            balance = Money.normalize(balance.subtract(principalComp, MC)).coerceAtLeast(MONEY_ZERO)
            totalInterest = totalInterest.add(interest, MC)
            val rowInstalment = Money.normalize(interest.add(principalComp, MC))
            rows += ScheduleRow(chargeDate, interest, principalComp, balance, rowInstalment)

            if (balance <= MONEY_ZERO) { payoffDate = chargeDate; break }
            firstPeriod = false
            if (remainingTerm != Int.MAX_VALUE) remainingTerm--
            chargeDate = chargeDate.plusMonths(1).let { chargeDateInMonth(YearMonth.from(it), terms.chargeDay) }
            periods++
        }

        return Projection(rows, Money.normalize(totalInterest), payoffDate)
    }

    /** The balance the schedule reports as of [asOfDate]: after the last charge on or before it. */
    fun balanceAt(
        terms: PartTerms,
        revisions: List<RevisionInput>,
        prepayments: List<PrepaymentInput>,
        asOfDate: LocalDate,
        anchor: AnchorInput? = null,
    ): BigDecimal {
        if (asOfDate.isBefore(terms.startDate)) return MONEY_ZERO
        val projection = project(terms, revisions, prepayments, horizon = asOfDate, anchor = anchor)
        val last = projection.rows.lastOrNull { !it.date.isAfter(asOfDate) }
        // A re-anchor takes precedence from its date: if it's in effect at asOf and no charge has been
        // applied since (the last charge on/before asOf predates the anchor, or there is none), the
        // balance is exactly the anchored value — the origin schedule no longer overwrites it.
        if (anchor != null && !anchor.date.isAfter(asOfDate) && (last == null || last.date.isBefore(anchor.date))) {
            return Money.normalize(anchor.balance).coerceAtLeast(MONEY_ZERO)
        }
        if (last != null) return last.balance
        // Before the first charge: current state less any prepayment already made.
        val prepaidBefore = prepayments.filter { !it.date.isAfter(asOfDate) }.fold(BigDecimal.ZERO) { a, p -> a + p.amount }
        return Money.normalize(terms.principal.subtract(prepaidBefore, MC)).coerceAtLeast(MONEY_ZERO)
    }

    private fun resolveInstalment(
        method: AmortizationMethod,
        balance: BigDecimal,
        monthly: BigDecimal,
        remainingTerm: Int,
        supplied: BigDecimal?,
    ): BigDecimal {
        return when (method) {
            AmortizationMethod.french -> {
                if (supplied != null && supplied > BigDecimal.ZERO) return Money.normalize(supplied)
                val n = if (remainingTerm == Int.MAX_VALUE || remainingTerm <= 0) 1 else remainingTerm
                if (monthly.signum() == 0) return Money.normalize(balance.divide(BigDecimal(n), MC))
                // M = P * i / (1 - (1+i)^-n)
                val onePlusI = BigDecimal.ONE.add(monthly, MC)
                val denom = BigDecimal.ONE.subtract(pow(onePlusI, -n), MC)
                if (denom.signum() == 0) return Money.normalize(balance)
                Money.normalize(balance.multiply(monthly, MC).divide(denom, MC))
            }
            // Instalment for these methods is derived per period from interest + principal component.
            else -> BigDecimal.ZERO
        }
    }

    private fun monthlyRate(annualPercent: BigDecimal): BigDecimal =
        annualPercent.divide(HUNDRED, MC).divide(TWELVE, MC)

    /**
     * Number of monthly instalments to repay [principal] at [monthly] rate with a constant [instalment]
     * (French). Used to give an instalment-driven part a concrete term. Double math is fine — the result
     * is just an integer period count.
     */
    private fun frenchPeriods(principal: BigDecimal, monthly: BigDecimal, instalment: BigDecimal): Int {
        if (instalment <= BigDecimal.ZERO) return MAX_PERIODS
        val p = principal.toDouble()
        val m = instalment.toDouble()
        val i = monthly.toDouble()
        if (i <= 0.0) return Math.ceil(p / m).toInt().coerceIn(1, MAX_PERIODS)
        val periodInterest = p * i
        if (m <= periodInterest) return MAX_PERIODS // never amortizes
        val n = -Math.log(1.0 - periodInterest / m) / Math.log(1.0 + i)
        return Math.ceil(n).toInt().coerceIn(1, MAX_PERIODS)
    }

    /** (base)^exp for integer exp, positive or negative, via MathContext. */
    private fun pow(base: BigDecimal, exp: Int): BigDecimal {
        if (exp == 0) return BigDecimal.ONE
        val positive = base.pow(kotlin.math.abs(exp), MC)
        return if (exp > 0) positive else BigDecimal.ONE.divide(positive, MC)
    }

    /**
     * Fraction of the first cycle the loan actually existed: days(startDate → firstCharge) over the
     * days of the month-long cycle ending at firstCharge. 1.0 when the start sits on the cycle boundary
     * (a full month), less when the start falls mid-cycle (odd-days / partial first period).
     */
    private fun firstPeriodFraction(startDate: LocalDate, firstCharge: LocalDate): BigDecimal {
        val cycleStart = firstCharge.minusMonths(1)
        val cycleDays = ChronoUnit.DAYS.between(cycleStart, firstCharge)
        val accrualDays = ChronoUnit.DAYS.between(startDate, firstCharge)
        if (cycleDays <= 0 || accrualDays >= cycleDays) return BigDecimal.ONE
        if (accrualDays <= 0) return BigDecimal.ZERO
        return BigDecimal(accrualDays).divide(BigDecimal(cycleDays), MC)
    }

    private fun firstChargeDate(startDate: LocalDate, chargeDay: Int): LocalDate {
        val sameMonth = chargeDateInMonth(YearMonth.from(startDate), chargeDay)
        return if (sameMonth.isAfter(startDate)) sameMonth
        else chargeDateInMonth(YearMonth.from(startDate.plusMonths(1)), chargeDay)
    }

    private fun chargeDateInMonth(ym: YearMonth, chargeDay: Int): LocalDate =
        ym.atDay(chargeDay.coerceIn(1, ym.lengthOfMonth()))

    private val MONEY_ZERO: BigDecimal = Money.normalize(BigDecimal.ZERO)
}
