package com.sephilabs.sharedledger.fire

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Seeds and fixed conventions for the FIRE projection; anything a household can override lives in
 *  `fire_settings`. */
object FireDefaults {

    val DEFAULT_EXPECTED_INFLATION_PCT: BigDecimal = BigDecimal("2.0")
    val DEFAULT_SAFE_WITHDRAWAL_RATE_PCT: BigDecimal = BigDecimal("4.0")
    val DEFAULT_FAT_FIRE_MULTIPLIER: BigDecimal = BigDecimal("1.5")

    /** Used for the taxable-gain share of withdrawals only while movements are insufficient to derive it. */
    val DEFAULT_FALLBACK_GAIN_FRACTION_PCT: BigDecimal = BigDecimal("50.0")

    /** Spanish savings-base scale (Ley 7/2024, FY 2025-2026). Seed only: every household gets an editable copy
     *  in `fire_tax_brackets`. */
    val SPANISH_SAVINGS_TAX_BRACKETS: List<TaxBracket> = listOf(
        TaxBracket(BigDecimal("0.00"), BigDecimal("19.0")),
        TaxBracket(BigDecimal("6000.00"), BigDecimal("21.0")),
        TaxBracket(BigDecimal("50000.00"), BigDecimal("23.0")),
        TaxBracket(BigDecimal("200000.00"), BigDecimal("27.0")),
        TaxBracket(BigDecimal("300000.00"), BigDecimal("30.0")),
    )

    const val MONTHS_PER_YEAR: Int = 12

    /** Spending bases and derived contributions average over at most this many trailing months. */
    const val TRAILING_WINDOW_MONTHS: Int = 12

    const val DAYS_PER_YEAR: Double = 365.25

    /** Monte Carlo yearly return samples are clamped to this many standard deviations around the mean. */
    const val RETURN_CLAMP_STDDEVS: Double = 3.0

    /** A sub-period must span at least this many days before its annualized return joins the historical sample. */
    const val MIN_HISTORICAL_PERIOD_DAYS: Long = 90

    /** The actual (XIRR) return is only trustworthy if movements cover contributions since the first snapshot in
     *  range. A larger lag flags the uncovered history as possibly overstating the return. */
    const val MOVEMENT_COVERAGE_GAP_MONTHS: Long = 1

    /** With no expense at all in this many latest complete months of the spending window, the photo warns that
     *  spending data has stopped coming in. */
    const val STALE_SPENDING_MONTHS: Int = 2

    /** Lower bound used when scanning history "since inception" (predates any real data). */
    val EARLIEST_DATA_DATE: LocalDate = LocalDate.of(2000, 1, 1)

    /** Nominal arithmetic annual mean & std dev (%) for new households: history-anchored with a modest haircut;
     *  volatilities match bonds-heavy (5%), 60/40 mixed (10%), and all-equity (15%) portfolios. */
    val DEFAULT_RETURN_SCENARIOS: List<ReturnScenario> = listOf(
        ReturnScenario(BigDecimal("4.0"), BigDecimal("5.0")),
        ReturnScenario(BigDecimal("6.5"), BigDecimal("10.0")),
        ReturnScenario(BigDecimal("8.5"), BigDecimal("15.0")),
    )

    /** The names the UI gives unnamed scenarios, in every language it speaks (lower case). A stored name equal to one
     *  of them is a leftover of when defaults were stored as text, so it counts as no name at all. */
    private val DEFAULT_SCENARIO_NAMES: Set<String> = setOf("conservative", "base", "optimistic", "conservador", "optimista")
    private val DEFAULT_NUMBERED_SCENARIO_NAME = Regex("^(scenario|escenario) \\d+$")

    fun isDefaultScenarioName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in DEFAULT_SCENARIO_NAMES || DEFAULT_NUMBERED_SCENARIO_NAME.matches(n)
    }

    const val DEFAULT_HORIZON_YEARS_AHEAD: Int = 25
    const val DEFAULT_HORIZON_ROUNDING_YEARS: Int = 5

    fun defaultTargetYear(currentYear: Int = LocalDate.now().year): Short {
        val raw = currentYear + DEFAULT_HORIZON_YEARS_AHEAD
        val r = DEFAULT_HORIZON_ROUNDING_YEARS
        return (((raw + r / 2) / r) * r).toShort()
    }

    /** Exact log-normal parameters (muLn, sigmaLn) for an arithmetic annual mean and std dev (both decimal). */
    fun logNormalParams(mean: Double, stdDev: Double): Pair<Double, Double> {
        val onePlusMean = 1.0 + mean
        val varianceRatio = (stdDev / onePlusMean) * (stdDev / onePlusMean)
        val sigmaLn = sqrt(ln(1.0 + varianceRatio))
        val muLn = ln(onePlusMean) - 0.5 * sigmaLn * sigmaLn
        return muLn to sigmaLn
    }

    /** Exact typical compound (median geometric) annual return exp(mu_ln) - 1 matching the Monte Carlo sampler. */
    fun typicalCompoundReturn(mean: Double, stdDev: Double): Double {
        if (stdDev == 0.0 || 1.0 + mean <= 0.0) return mean.coerceAtLeast(-0.9999)
        val (muLn, _) = logNormalParams(mean, stdDev)
        return exp(muLn) - 1.0
    }
}
