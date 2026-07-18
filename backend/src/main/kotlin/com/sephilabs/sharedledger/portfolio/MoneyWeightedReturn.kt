package com.sephilabs.sharedledger.portfolio

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * Money-weighted return (XIRR) over the portfolio's lot cash flows. Pure math, no
 * pricing or persistence: the caller supplies the dated flows (buys as outflows,
 * sells as inflows, the current value of open holdings as a terminal inflow) already
 * converted to the base currency at their trade-time FX rates.
 *
 * The rate is the root of the net-present-value function, found by bisection with an
 * expanding upper bracket — slower than Newton but never diverges, and the expansion
 * covers the huge annualized rates short histories produce. BigDecimal at the
 * boundaries; the iterative root finding runs in Double.
 *
 * Deliberately independent from the FIRE domain's snapshot-based return: different
 * data source, different scope.
 */
object MoneyWeightedReturn {

    /** Signed base-currency flow: money put in (buys) negative, money coming back (sells, terminal value) positive. */
    data class Flow(val date: LocalDate, val amountBase: BigDecimal)

    enum class Failure {
        /** All live flows point the same way (e.g. only buys and a worthless position): NPV has no root. */
        NO_SIGN_CHANGE,

        /** Every flow shares one date: NPV is constant in the rate, so no rate is meaningful. */
        SINGLE_DAY_SPAN,

        /** The bisection did not settle inside the iteration cap (defensive; not expected in practice). */
        NO_CONVERGENCE,
    }

    data class Result(
        /**
         * Scale-4 fraction (0.1234 = +12.34 %): the annualized rate, or the cumulative
         * money-weighted return when [annualized] is false. Null iff [failure] is set.
         */
        val value: BigDecimal?,
        /** False when the history spans less than [MIN_ANNUALIZATION_DAYS]: annualizing a short period produces absurd numbers. */
        val annualized: Boolean,
        val failure: Failure?,
    )

    const val DAYS_PER_YEAR: Double = 365.25

    /** Histories shorter than this report the cumulative return instead of an annualized rate. */
    const val MIN_ANNUALIZATION_DAYS: Long = 365L

    /** Just above −100 %: the discount factor explodes below and no real portfolio lives there. */
    const val RATE_FLOOR: Double = -0.999999

    /** +1000 % annualized: the initial upper bracket, generous for a multi-year household portfolio. */
    const val INITIAL_RATE_CEILING: Double = 10.0

    /** The upper bracket grows by this factor while NPV has not changed sign yet. */
    const val RATE_CEILING_GROWTH: Double = 10.0

    /** Beyond this annualized rate the search gives up ([Failure.NO_SIGN_CHANGE]). */
    const val MAX_RATE_CEILING: Double = 1e6

    const val MAX_ITERATIONS: Int = 200
    const val NPV_EPSILON: Double = 1e-7

    /** Bracket width below which the midpoint is accepted even if NPV has not hit [NPV_EPSILON]. */
    const val RATE_EPSILON: Double = 1e-10

    /**
     * Money-weighted return of [flows] with the history classified against [asOf] (the
     * terminal-value date): annualized for spans of at least [MIN_ANNUALIZATION_DAYS],
     * cumulative otherwise. Zero-amount flows are ignored.
     */
    fun compute(flows: List<Flow>, asOf: LocalDate): Result {
        val live = flows.filter { it.amountBase.signum() != 0 }
        if (live.none { it.amountBase.signum() < 0 } || live.none { it.amountBase.signum() > 0 }) {
            return failure(Failure.NO_SIGN_CHANGE)
        }
        if (live.map { it.date }.distinct().size == 1) return failure(Failure.SINGLE_DAY_SPAN)

        val t0 = live.minOf { it.date }
        val years = live.map { ChronoUnit.DAYS.between(t0, it.date).toDouble() / DAYS_PER_YEAR }
        val amounts = live.map { it.amountBase.toDouble() }

        fun npv(rate: Double): Double {
            var sum = 0.0
            for (i in amounts.indices) sum += amounts[i] / (1.0 + rate).pow(years[i])
            return sum
        }

        val annualRate = solve(::npv) ?: return failure(Failure.NO_SIGN_CHANGE)
        if (annualRate.isNaN()) return failure(Failure.NO_CONVERGENCE)

        val spanDays = ChronoUnit.DAYS.between(t0, asOf)
        val annualized = spanDays >= MIN_ANNUALIZATION_DAYS
        val value =
            if (annualized) annualRate
            else (1.0 + annualRate).pow(spanDays.toDouble() / DAYS_PER_YEAR) - 1.0
        return Result(
            value = BigDecimal.valueOf(value).setScale(PortfolioValuationCalculator.FRACTION_SCALE, RoundingMode.HALF_EVEN),
            annualized = annualized,
            failure = null,
        )
    }

    /**
     * Root of [npv] by bisection over [[RATE_FLOOR], ceiling], expanding the ceiling by
     * [RATE_CEILING_GROWTH] up to [MAX_RATE_CEILING] until the endpoints straddle zero.
     * Null when no sign change exists; NaN when the loop somehow fails to settle.
     */
    private fun solve(npv: (Double) -> Double): Double? {
        var lo = RATE_FLOOR
        var npvLo = npv(lo)
        if (npvLo == 0.0) return lo

        var hi = INITIAL_RATE_CEILING
        var npvHi = npv(hi)
        while (npvLo * npvHi > 0.0) {
            if (hi >= MAX_RATE_CEILING) return null
            hi = (hi * RATE_CEILING_GROWTH).coerceAtMost(MAX_RATE_CEILING)
            npvHi = npv(hi)
        }
        if (npvHi == 0.0) return hi

        repeat(MAX_ITERATIONS) {
            val mid = (lo + hi) / 2.0
            val npvMid = npv(mid)
            if (abs(npvMid) < NPV_EPSILON || hi - lo < RATE_EPSILON) return mid
            if (npvLo * npvMid < 0.0) {
                hi = mid
            } else {
                lo = mid
                npvLo = npvMid
            }
        }
        return Double.NaN
    }

    private fun failure(reason: Failure) = Result(value = null, annualized = false, failure = reason)
}
