package com.sephilabs.sharedledger.fire

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate

data class ReturnScenarioInput(
    @field:NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
    val historical: Boolean = false,
)

data class ReturnScenarioDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
    val historical: Boolean,
)

data class TaxBracketInput(
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val lowerBound: BigDecimal,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @field:DecimalMax(value = "100.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val ratePct: BigDecimal,
)

data class TaxBracketDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val lowerBound: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val ratePct: BigDecimal,
)

data class FireSettingsDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetAmount: BigDecimal,
    val targetYear: Short,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyContribution: BigDecimal,
    val returnScenarios: List<ReturnScenarioDto>,
    val qualifyingAssetClasses: List<String>,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expectedInflationPct: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val safeWithdrawalRatePct: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fatMultiplier: BigDecimal,
    val contributionMode: ContributionMode,
    val indexContribution: Boolean,
    val essentialSpendingMode: SpendingBaseMode,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val manualEssentialSpending: BigDecimal,
    val totalSpendingMode: SpendingBaseMode,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val manualTotalSpending: BigDecimal,
    val tierLeanEnabled: Boolean,
    val tierFireEnabled: Boolean,
    val tierFatEnabled: Boolean,
    val tierCustomEnabled: Boolean,
    val applyCapitalGainsTax: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fallbackGainFractionPct: BigDecimal,
    val taxBrackets: List<TaxBracketDto>,
)

data class FireSettingsRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetAmount: BigDecimal,
    @field:NotNull val targetYear: Short,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyContribution: BigDecimal,
    @field:NotNull @field:Valid val returnScenarios: List<ReturnScenarioInput>,
    @field:NotNull val qualifyingAssetClasses: List<String>,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @field:DecimalMax(value = "50.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expectedInflationPct: BigDecimal,
    @field:NotNull
    @field:DecimalMin(value = "0.01")
    @field:DecimalMax(value = "100.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val safeWithdrawalRatePct: BigDecimal,
    @field:NotNull
    @field:DecimalMin(value = "1.00")
    @field:DecimalMax(value = "100.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fatMultiplier: BigDecimal,
    @field:NotNull val contributionMode: ContributionMode,
    val indexContribution: Boolean = true,
    val essentialSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val manualEssentialSpending: BigDecimal = BigDecimal.ZERO,
    val totalSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val manualTotalSpending: BigDecimal = BigDecimal.ZERO,
    val tierLeanEnabled: Boolean = true,
    val tierFireEnabled: Boolean = true,
    val tierFatEnabled: Boolean = true,
    val tierCustomEnabled: Boolean = false,
    val applyCapitalGainsTax: Boolean = true,
    @field:NotNull
    @field:DecimalMin(value = "0.00")
    @field:DecimalMax(value = "100.00")
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fallbackGainFractionPct: BigDecimal,
    @field:NotNull @field:Valid val taxBrackets: List<TaxBracketInput>,
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

/** Hit statistics of one tier under one return scenario, with and without further contributions. */
data class FireTierScenarioStats(
    val tier: FireTierKey,
    /** First year the deterministic mean-return path crosses the tier's (inflated) target. */
    val deterministicHitYear: Int?,
    /** Fraction of trials reaching the tier's inflated target at any year ≤ targetYear. */
    val probabilityOfReachingTarget: Double,
    val medianYearReachingTarget: Int?,
    /** Same statistics with contributions stopped today (Coast FIRE). */
    val coastProbabilityOfReachingTarget: Double,
    val coastMedianYearReachingTarget: Int?,
)

data class FireScenarioOutput(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
    val historical: Boolean,
    /** Deterministic year-by-year path using `meanPercent` only. */
    val series: List<FireScenarioYear>,
    /** Per-year p10/p25/p50/p75/p90 over the Monte Carlo trials. */
    val percentiles: List<FireScenarioPercentiles>,
    /** One row per active, computable tier. */
    val tierStats: List<FireTierScenarioStats>,
)

/** One FIRE tier sized from the household's real spending (or the manual custom target). */
data class FireTierOutput(
    val key: FireTierKey,
    val enabled: Boolean,
    /** Monthly spending base in today's terms; null for the custom tier. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyNetSpending: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualNetSpending: BigDecimal?,
    /** Annual withdrawal needed to cover the net spending after capital-gains tax (= net when tax is off). */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualGrossSpending: BigDecimal?,
    /** Estimated tax paid per year at the target, today's terms (gross − net). */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val estimatedAnnualTax: BigDecimal?,
    /** Wealth target in today's terms; null when the tier is not computable ("no target configured"). */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetToday: BigDecimal?,
    /** current_qualifying_wealth / target, as a percentage; null without a target. */
    val coveragePercent: Double?,
    /** Year-by-year inflated target for the chart; empty when the tier is not computable. */
    val targetCurve: List<FireScenarioYear>,
)

/** The single definition of the spending bases. `essentialMonthly`/`totalMonthly` are the EFFECTIVE values
 *  (manual override when active, live trailing-12 otherwise) and feed everything downstream; the derived
 *  values ride alongside so the UI can show the gap against an override. */
data class FireSpendingBasis(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val essentialMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val derivedEssentialMonthly: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val derivedTotalMonthly: BigDecimal,
    val essentialMode: SpendingBaseMode,
    val totalMode: SpendingBaseMode,
    /** Months backing the derived averages (0–12); irrelevant to a manual base. */
    val monthsAvailable: Int,
)

data class FireContributions(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val manualMonthly: BigDecimal,
    /** Trailing-12 average of (income − expenses); null without transactions. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val savingsMonthly: BigDecimal?,
    /** Trailing-12 average of net contributions into qualifying classes; null without movements. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val movementsMonthly: BigDecimal?,
    val mode: ContributionMode,
    /** The value actually feeding the projection; null when the chosen derived mode lacks data. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val activeMonthly: BigDecimal?,
)

data class FireActualReturn(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualizedPercent: BigDecimal,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val movementCount: Int,
    /** Date of the earliest movement counted in the XIRR (the start of real coverage). */
    val firstMovementDate: LocalDate,
    /** Whole months between the first snapshot and the first movement, 0 unless the gap exceeds the threshold. */
    val uncoveredMonths: Int,
)

enum class FireActualReturnUnavailableReason { insufficient_snapshots, no_movements, not_computable }

/** Mean/stddev of the household's own yearly money-weighted returns — reference only. */
data class FireHistoricalScenario(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val meanPercent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val stdDevPercent: BigDecimal,
    val yearsOfData: Int,
)

data class FireGainFraction(
    /** Share of a withdrawal that is realized gain rather than principal, 0–100. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val percent: BigDecimal,
    /** "movements" when derived from recorded net contributions, "manual" for the fallback setting. */
    val source: String,
)

data class FireHistoricalContribution(
    val year: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val cumulative: BigDecimal,
)

data class FireProjectionResponse(
    val startYear: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val startingValue: BigDecimal,
    val snapshotDate: LocalDate?,
    val settings: FireSettingsDto,
    val spending: FireSpendingBasis,
    val contributions: FireContributions,
    val gainFraction: FireGainFraction,
    val actualReturn: FireActualReturn?,
    val actualReturnUnavailableReason: FireActualReturnUnavailableReason?,
    val historicalScenario: FireHistoricalScenario?,
    val tiers: List<FireTierOutput>,
    val scenarios: List<FireScenarioOutput>,
    val cumulativeContributions: List<FireHistoricalContribution>,
    val monteCarloTrials: Int,
)
