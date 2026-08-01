package com.sephilabs.sharedledger.fire

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/** Money-weighted (XIRR) annualized return over an irregular cash-flow series. Money in (initial value,
 *  contributions) is negative; money out plus the terminal value is positive. The NPV root is found by
 *  bisection — slower than Newton but it never diverges. */
object Xirr {

    data class CashFlow(val date: LocalDate, val amount: Double)

    /** -99.99%: below this the discount factor explodes and no real portfolio lives there. */
    const val RATE_LOWER_BOUND: Double = -0.9999

    /** +1000% annualized, a generous ceiling for a household portfolio. */
    const val RATE_UPPER_BOUND: Double = 10.0

    const val MAX_ITERATIONS: Int = 200
    const val NPV_EPSILON: Double = 1e-7

    /** Annualized rate for [flows], or null when the series has no sign change or no root inside
     *  [RATE_LOWER_BOUND, RATE_UPPER_BOUND]. */
    fun rate(flows: List<CashFlow>): Double? {
        val live = flows.filter { it.amount != 0.0 }
        if (live.none { it.amount < 0.0 } || live.none { it.amount > 0.0 }) return null
        val t0 = live.minOf { it.date }
        val years = live.map { ChronoUnit.DAYS.between(t0, it.date).toDouble() / FireDefaults.DAYS_PER_YEAR }
        val amounts = live.map { it.amount }

        fun npv(rate: Double): Double {
            var sum = 0.0
            for (i in amounts.indices) sum += amounts[i] / (1.0 + rate).pow(years[i])
            return sum
        }

        var lo = RATE_LOWER_BOUND
        var hi = RATE_UPPER_BOUND
        var npvLo = npv(lo)
        val npvHi = npv(hi)
        if (npvLo == 0.0) return lo
        if (npvHi == 0.0) return hi
        if (npvLo * npvHi > 0.0) return null

        repeat(MAX_ITERATIONS) {
            val mid = (lo + hi) / 2.0
            val npvMid = npv(mid)
            if (abs(npvMid) < NPV_EPSILON) return mid
            if (npvLo * npvMid < 0.0) {
                hi = mid
            } else {
                lo = mid
                npvLo = npvMid
            }
        }
        return (lo + hi) / 2.0
    }
}
