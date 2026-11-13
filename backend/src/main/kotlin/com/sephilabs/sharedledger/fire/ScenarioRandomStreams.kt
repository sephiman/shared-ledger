package com.sephilabs.sharedledger.fire

import java.util.SplittableRandom

/** Fresh, seeded random streams per projection and scenario: the same inputs always give the same draws, and
 *  every what-if, lever and historical point sees the same yearly return shocks as the photo. */
class ScenarioRandomStreams(private val scenarioIndex: Int) {

    fun returns(): SplittableRandom = stream(RETURNS)

    fun drawdown(): SplittableRandom = stream(DRAWDOWN)

    fun coast(): SplittableRandom = stream(COAST)

    private fun stream(kind: Int): SplittableRandom =
        SplittableRandom(MONTE_CARLO_SEED + scenarioIndex.toLong() * STREAMS_PER_SCENARIO + kind)

    private companion object {
        const val MONTE_CARLO_SEED: Long = 20_261_113L
        const val STREAMS_PER_SCENARIO: Long = 3
        const val RETURNS = 0
        const val DRAWDOWN = 1
        const val COAST = 2
    }
}
