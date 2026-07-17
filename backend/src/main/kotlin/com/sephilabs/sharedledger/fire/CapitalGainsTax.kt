package com.sephilabs.sharedledger.fire

import java.math.BigDecimal

/**
 * One bracket of a progressive capital-gains scale. The upper bound is implicit:
 * the next bracket's lower bound (the last bracket is open-ended).
 */
data class TaxBracket(
    val lowerBound: BigDecimal,
    val ratePct: BigDecimal,
)

/**
 * Progressive taxation of realized capital gains, and the closed-form net→gross conversion
 * used to size FIRE targets. The math runs on doubles inside the Monte Carlo hot path
 * (per trial, per year, per tier), so brackets are compiled to flat arrays first.
 *
 * Model notes (deliberate simplifications, surfaced in the UI): only the gain share of a
 * withdrawal is taxable, loss offsetting is ignored, and bracket thresholds stay nominal.
 */
object CapitalGainsTax {

    fun compile(brackets: List<TaxBracket>): Compiled {
        val sorted = brackets.sortedBy { it.lowerBound }
        val lowers = DoubleArray(sorted.size) { sorted[it].lowerBound.toDouble() }
        val rates = DoubleArray(sorted.size) { sorted[it].ratePct.toDouble() / 100.0 }
        // Cumulative tax owed on everything below each bracket's lower bound.
        val taxBelow = DoubleArray(sorted.size)
        for (i in 1 until sorted.size) {
            taxBelow[i] = taxBelow[i - 1] + (lowers[i] - lowers[i - 1]) * rates[i - 1]
        }
        return Compiled(lowers, rates, taxBelow)
    }

    fun taxOn(gain: Double, brackets: List<TaxBracket>): Double = compile(brackets).taxOn(gain)

    fun grossUp(net: Double, gainFraction: Double, brackets: List<TaxBracket>): Double =
        compile(brackets).grossUp(net, gainFraction)

    class Compiled internal constructor(
        private val lowers: DoubleArray,
        private val rates: DoubleArray,
        private val taxBelow: DoubleArray,
    ) {

        val isEmpty: Boolean get() = lowers.isEmpty()

        /** Progressive tax owed on a realized gain. Gains below the first bound are untaxed. */
        fun taxOn(gain: Double): Double {
            if (gain <= 0.0 || lowers.isEmpty()) return 0.0
            var tax = 0.0
            for (i in lowers.indices) {
                if (gain <= lowers[i]) break
                val upper = if (i + 1 < lowers.size) lowers[i + 1] else Double.POSITIVE_INFINITY
                tax += (minOf(gain, upper) - lowers[i]) * rates[i]
            }
            return tax
        }

        /**
         * Gross annual withdrawal `W` needed to be left with [net] to spend after taxes, when
         * [gainFraction] of every euro withdrawn is realized gain: solves
         * `W − taxOn(W × gainFraction) = net`.
         *
         * The equation is piecewise linear in `W`, so within the bracket that `W × gainFraction`
         * lands in it has the closed-form solution
         * `W = (net + taxBelow − rate × lowerBound) / (1 − rate × gainFraction)`;
         * brackets are scanned from the bottom and the first self-consistent solution wins.
         */
        fun grossUp(net: Double, gainFraction: Double): Double {
            if (net <= 0.0 || lowers.isEmpty()) return net
            val g = gainFraction.coerceIn(0.0, 1.0)
            if (g == 0.0) return net
            // Withdrawals whose taxable gain stays below the first bound are untaxed.
            if (net * g < lowers[0]) return net

            for (i in lowers.indices) {
                val upper = if (i + 1 < lowers.size) lowers[i + 1] else Double.POSITIVE_INFINITY
                val denominator = 1.0 - rates[i] * g
                if (denominator <= 0.0) continue
                val gross = (net + taxBelow[i] - rates[i] * lowers[i]) / denominator
                val taxableGain = gross * g
                if (taxableGain >= lowers[i] && (taxableGain < upper || i == lowers.size - 1)) return gross
            }
            // Unreachable with a well-formed ascending scale; degrade to the untaxed amount.
            return net
        }
    }
}
