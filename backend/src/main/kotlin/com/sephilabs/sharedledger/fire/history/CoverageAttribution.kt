package com.sephilabs.sharedledger.fire.history

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Splits a change in coverage C = W / T (in percentage points) into what you put in, what the market did and the
 * change in what you need — the two-factor Shapley (midpoint) split, so the order the causes are measured in does
 * not matter and the cross term is shared half and half:
 *
 *   wealth part   = ΔW × ½(1/T₀ + 1/T₁)        → contributions N and market (ΔW − N), split linearly
 *   need part     = ½(W₀ + W₁) × (1/T₁ − 1/T₀)
 *
 * The parts add up to C₁ − C₀ algebraically. Without a trustworthy N the wealth part stays merged.
 */
object CoverageAttribution {

    class Endpoint(val wealth: BigDecimal, val target: BigDecimal)

    class Parts(
        val total: Double,
        val contributed: Double?,
        val market: Double?,
        val wealth: Double,
        val need: Double,
    )

    fun split(from: Endpoint, to: Endpoint, netContributions: BigDecimal?): Parts {
        val w0 = from.wealth.toDouble()
        val w1 = to.wealth.toDouble()
        val inv0 = 1.0 / from.target.toDouble()
        val inv1 = 1.0 / to.target.toDouble()
        val total = (w1 * inv1 - w0 * inv0) * PERCENT
        val need = 0.5 * (w0 + w1) * (inv1 - inv0) * PERCENT
        // The wealth part is defined as the remainder so the three parts sum to the total in floating point too.
        val wealth = total - need
        val contributed = netContributions?.let { it.toDouble() * 0.5 * (inv0 + inv1) * PERCENT }
        val market = contributed?.let { wealth - it }
        return Parts(total, contributed, market, wealth, need)
    }

    /** Rounds parts to tenths with largest remainder so the shown parts add up to the shown (rounded) total. */
    fun roundToTenths(parts: List<Double>, total: Double): List<BigDecimal> {
        val units = parts.map { it * TENTHS }
        val floors = units.map { floor(it).toLong() }
        val target = (total * TENTHS).roundToLong()
        var remaining = target - floors.sum()
        val order = units.indices.sortedByDescending { units[it] - floors[it] }
        val result = floors.toLongArray()
        for (i in order) {
            if (remaining <= 0) break
            result[i] += 1
            remaining--
        }
        return result.map { BigDecimal.valueOf(it).divide(BigDecimal.TEN, 1, RoundingMode.UNNECESSARY) }
    }

    private const val PERCENT = 100.0
    private const val TENTHS = 10.0
}
