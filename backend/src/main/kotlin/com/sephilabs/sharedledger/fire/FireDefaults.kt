package com.sephilabs.sharedledger.fire

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Named defaults and conventions for the FIRE projection. Values that a household can
 * override live in `fire_settings`; these are only the seeds and the fixed conventions.
 */
object FireDefaults {

    val DEFAULT_EXPECTED_INFLATION_PCT: BigDecimal = BigDecimal("2.0")
    val DEFAULT_SAFE_WITHDRAWAL_RATE_PCT: BigDecimal = BigDecimal("4.0")
    val DEFAULT_FAT_FIRE_MULTIPLIER: BigDecimal = BigDecimal("1.5")

    /** Used for the taxable-gain share of withdrawals only while movements are insufficient to derive it. */
    val DEFAULT_FALLBACK_GAIN_FRACTION_PCT: BigDecimal = BigDecimal("50.0")

    /**
     * Spanish savings-base scale (Ley 7/2024, fiscal years 2025-2026). Seed values only:
     * every household gets an editable copy in `fire_tax_brackets`.
     */
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

    /** Lower bound used when scanning history "since inception" (predates any real data). */
    val EARLIEST_DATA_DATE: LocalDate = LocalDate.of(2000, 1, 1)
}
