package com.sephilabs.sharedledger.fire

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ReturnScenario(
    val meanPercent: BigDecimal = BigDecimal.ZERO,
    val stdDevPercent: BigDecimal = BigDecimal.ZERO,
    /** Marks the "(historical — reference)" scenario derived from the household's own returns. */
    val historical: Boolean = false,
    val name: String = "",
)

/** Source feeding the projection's monthly contribution. */
enum class ContributionMode { manual, savings, movements }

/** Source feeding a tier's spending base: live trailing data or a manual monthly amount. */
enum class SpendingBaseMode { derived, manual }

/** How monthly spending is aggregated across the lookback window. */
enum class SpendingAggregationMode { mean, median }

enum class FireTierKey { lean, fire, fat, custom }

enum class CashFlowEventKind { lump_sum, contribution_delta, spending_delta }

data class FireCashFlowEvent(
    val year: Int = 2030,
    val kind: CashFlowEventKind = CashFlowEventKind.lump_sum,
    val amount: BigDecimal = BigDecimal.ZERO,
    val label: String = "",
)

@Entity
@Table(name = "fire_settings")
class FireSettings(
    @Id
    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    /** The optional "custom" tier's wealth target, in today's nominal terms. */
    @Column(name = "target_amount", nullable = false, precision = 15, scale = 2)
    var targetAmount: BigDecimal = BigDecimal.ZERO,

    /** Projection horizon: simulations run from the latest snapshot's year up to this year. */
    @Column(name = "target_year", nullable = false)
    var targetYear: Short = 2050,

    @Column(name = "monthly_contribution", nullable = false, precision = 15, scale = 2)
    var monthlyContribution: BigDecimal = BigDecimal.ZERO,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "return_scenarios", nullable = false, columnDefinition = "jsonb")
    var returnScenarios: List<ReturnScenario> = listOf(
        ReturnScenario(BigDecimal("4.0"), BigDecimal("5.0"), name = "Conservative"),
        ReturnScenario(BigDecimal("6.0"), BigDecimal("12.0"), name = "Base"),
        ReturnScenario(BigDecimal("8.0"), BigDecimal("18.0"), name = "Optimistic"),
    ),

    @Column(name = "qualifying_asset_classes", nullable = false, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var qualifyingAssetClasses: Array<String> = arrayOf("fund", "etfs", "stocks", "crypto", "pension"),

    @Column(name = "expected_inflation_pct", nullable = false, precision = 5, scale = 2)
    var expectedInflationPct: BigDecimal = FireDefaults.DEFAULT_EXPECTED_INFLATION_PCT,

    @Column(name = "safe_withdrawal_rate_pct", nullable = false, precision = 5, scale = 2)
    var safeWithdrawalRatePct: BigDecimal = FireDefaults.DEFAULT_SAFE_WITHDRAWAL_RATE_PCT,

    @Column(name = "fat_multiplier", nullable = false, precision = 5, scale = 2)
    var fatMultiplier: BigDecimal = FireDefaults.DEFAULT_FAT_FIRE_MULTIPLIER,

    @Enumerated(EnumType.STRING)
    @Column(name = "contribution_mode", nullable = false, length = 16)
    var contributionMode: ContributionMode = ContributionMode.manual,

    @Enumerated(EnumType.STRING)
    @Column(name = "essential_spending_mode", nullable = false, length = 16)
    var essentialSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,

    /** Used only while essentialSpendingMode is manual; the derived value is never persisted. */
    @Column(name = "manual_essential_spending", nullable = false, precision = 15, scale = 2)
    var manualEssentialSpending: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "total_spending_mode", nullable = false, length = 16)
    var totalSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,

    /** Used only while totalSpendingMode is manual; the derived value is never persisted. */
    @Column(name = "manual_total_spending", nullable = false, precision = 15, scale = 2)
    var manualTotalSpending: BigDecimal = BigDecimal.ZERO,

    /** When true the monthly contribution grows with expected inflation year over year. */
    @Column(name = "index_contribution", nullable = false)
    var indexContribution: Boolean = true,

    @Column(name = "tier_lean_enabled", nullable = false)
    var tierLeanEnabled: Boolean = true,

    @Column(name = "tier_fire_enabled", nullable = false)
    var tierFireEnabled: Boolean = true,

    @Column(name = "tier_fat_enabled", nullable = false)
    var tierFatEnabled: Boolean = true,

    @Column(name = "tier_custom_enabled", nullable = false)
    var tierCustomEnabled: Boolean = false,

    @Column(name = "apply_capital_gains_tax", nullable = false)
    var applyCapitalGainsTax: Boolean = true,

    /** Manual estimate of the gain share of withdrawals, used only when movements can't derive it. */
    @Column(name = "fallback_gain_fraction_pct", nullable = false, precision = 5, scale = 2)
    var fallbackGainFractionPct: BigDecimal = FireDefaults.DEFAULT_FALLBACK_GAIN_FRACTION_PCT,

    /** When true, tax bracket lower bounds grow with expected inflation instead of remaining nominal. */
    @Column(name = "index_tax_brackets", nullable = false)
    var indexTaxBrackets: Boolean = false,

    /** Number of retirement years simulated after hitting a tier to measure sequence-of-returns survival. */
    @Column(name = "drawdown_years", nullable = false)
    var drawdownYears: Short = 30,

    /** Number of months averaged when deriving essential/total spending. */
    @Column(name = "spending_lookback_months", nullable = false)
    var spendingLookbackMonths: Short = 12,

    @Enumerated(EnumType.STRING)
    @Column(name = "spending_aggregation", nullable = false, length = 16)
    var spendingAggregation: SpendingAggregationMode = SpendingAggregationMode.mean,

    /** When true, derived spending ends at the last completed month rather than the current partial month. */
    @Column(name = "exclude_current_month", nullable = false)
    var excludeCurrentMonth: Boolean = false,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cash_flow_events", nullable = false, columnDefinition = "jsonb")
    var cashFlowEvents: List<FireCashFlowEvent> = emptyList(),

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_tier", nullable = false, length = 16)
    var referenceTier: FireTierKey = FireTierKey.fire,

    @Column(name = "reference_scenario_index", nullable = false)
    var referenceScenarioIndex: Short = 1,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Embeddable
data class FireTaxBracketId(
    @Column(name = "household_id", nullable = false)
    val householdId: UUID = UUID(0, 0),

    @Column(name = "lower_bound", nullable = false, precision = 15, scale = 2)
    val lowerBound: BigDecimal = BigDecimal.ZERO,
) : Serializable

@Entity
@Table(name = "fire_tax_brackets")
class FireTaxBracket(
    @EmbeddedId
    val id: FireTaxBracketId,

    @Column(name = "rate_pct", nullable = false, precision = 5, scale = 2)
    var ratePct: BigDecimal,
)
