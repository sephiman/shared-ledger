package com.sephilabs.sharedledger.fire

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class ReturnScenarioInput(
    @field:NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
)

data class ReturnScenarioDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
)

data class FireSettingsDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetAmount: BigDecimal,
    val targetYear: Short,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyContribution: BigDecimal,
    val returnScenarios: List<ReturnScenarioDto>,
    val qualifyingAssetClasses: List<String>,
)

data class FireSettingsRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetAmount: BigDecimal,
    @field:NotNull val targetYear: Short,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyContribution: BigDecimal,
    @field:NotNull val returnScenarios: List<ReturnScenarioInput>,
    @field:NotNull val qualifyingAssetClasses: List<String>,
)

data class FireScenarioYear(
    val year: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val value: BigDecimal,
)

data class FireScenarioPercentiles(
    val year: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val p10: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val p25: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val p50: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val p75: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val p90: BigDecimal,
)

data class FireScenarioOutput(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
    /** Deterministic year-by-year using `meanPercent` only. Retained for clarity. */
    val series: List<FireScenarioYear>,
    /** Earliest year on the deterministic path that crosses `targetAmount`, or null. */
    val targetHitYear: Int?,
    /** Per-year p10/p25/p50/p75/p90 over the Monte Carlo trials. Empty if MC is disabled. */
    val percentiles: List<FireScenarioPercentiles>,
    /** Fraction of trials whose portfolio reaches the target at any year ≤ targetYear. */
    val probabilityOfReachingTarget: Double,
    /** Median (over reaching trials) of the first year the target was crossed. Null if none reach. */
    val medianYearReachingTarget: Int?,
)

data class FireHistoricalContribution(
    val year: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val cumulative: BigDecimal,
)

data class FireProjectionResponse(
    val startYear: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val startingValue: BigDecimal,
    val settings: FireSettingsDto,
    val scenarios: List<FireScenarioOutput>,
    val actualAnnualizedReturnPercent: BigDecimal?,
    val cumulativeContributions: List<FireHistoricalContribution>,
    val monteCarloTrials: Int,
)
