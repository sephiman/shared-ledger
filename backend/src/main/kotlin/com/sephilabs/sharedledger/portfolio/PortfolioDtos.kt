package com.sephilabs.sharedledger.portfolio

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.common.validation.ValidCurrency
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class HoldingRequest(
    @field:NotNull val assetClass: HoldingAssetClass,
    @field:NotBlank val symbol: String,
    val label: String? = null,
    @field:ValidCurrency val nativeCurrency: String? = null,
    val isin: String? = null,
    // Optional immediate link when the user picked a search candidate during creation.
    val provider: HoldingProvider? = null,
    val providerSymbol: String? = null,
)

data class HoldingUpdateRequest(
    val symbol: String? = null,
    val label: String? = null,
    @field:ValidCurrency val nativeCurrency: String? = null,
    val isin: String? = null,
    val active: Boolean? = null,
)

data class LinkRequest(
    @field:NotNull val provider: HoldingProvider,
    @field:NotBlank val providerSymbol: String,
    @field:ValidCurrency val nativeCurrency: String? = null,
    val isin: String? = null,
)

data class LotRequest(
    val type: LotType = LotType.BUY,
    @field:NotNull val tradedOn: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @field:NotNull val quantity: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @field:NotNull val unitPrice: BigDecimal,
    @field:ValidCurrency val currency: String? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val fee: BigDecimal? = null,
    val note: String? = null,
)

data class LotDto(
    val id: UUID,
    val type: LotType,
    val tradedOn: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val quantity: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unitPrice: BigDecimal,
    val currency: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val fee: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val fxRateToBase: BigDecimal,
    val note: String?,
    // Cost for a BUY, proceeds for a SELL, in base currency.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amountBase: BigDecimal,
    // Per-lot FIFO breakdown, in base currency. Only populated on the holding-level view.
    // BUY: quantity of this lot still held after later sells; null for SELL.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val remainingQty: BigDecimal? = null,
    // BUY: base cost basis of the remaining quantity; null for SELL.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val remainingCostBasis: BigDecimal? = null,
    // BUY: realized gain on the already-sold portion (at sale prices). SELL: the sale's realized P&L.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val realizedPnl: BigDecimal? = null,
    // BUY only: unrealized P&L on the remaining quantity at the current price; null when unpriced.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unrealizedPnl: BigDecimal? = null,
)

data class HoldingDto(
    val id: UUID,
    val assetClass: HoldingAssetClass,
    val symbol: String,
    val label: String?,
    val nativeCurrency: String,
    val isin: String?,
    val provider: HoldingProvider?,
    val providerSymbol: String?,
    val linked: Boolean,
    val active: Boolean,
    val lots: List<LotDto>,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netQuantity: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val remainingCostBasis: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val realizedPnl: BigDecimal,
    val closed: Boolean,
    val createdAt: Instant,
)

data class HoldingSummaryDto(
    val holding: HoldingDto,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val currentPrice: BigDecimal?,
    val priceCurrency: String?,
    val priceAsOf: LocalDate?,
    // When the price was recorded (price_history.as_of), as opposed to the trading day
    // [priceAsOf] belongs to. Only this is fine-grained enough for an intraday cadence:
    // crypto's hourly row is re-observed all day long under one unchanged price date.
    val priceObservedAt: Instant?,
    val stale: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val currentValue: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unrealizedPnl: BigDecimal?,
    // Fraction, scale 4: 0.1234 = +12.34 %.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unrealizedPnlPct: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val realizedPnl: BigDecimal,
    // FIFO cost basis of this holding's sold lots, over the whole history.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val soldCostBasis: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalReturn: BigDecimal?,
    // Fraction of the priced portfolio value, scale 4.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val weight: BigDecimal?,
)

enum class MoneyWeightedReturnUnavailableReason {
    /** No lots registered at all. */
    no_flows,

    /** An open position has no price, so the terminal value would be incomplete. */
    unpriced_holdings,

    /** The flows admit no meaningful rate (no sign change, single-day span, non-convergence). */
    not_computable,
}

/** Money-weighted return (XIRR) over the whole lot history, every flow in base currency at its trade-time
 *  FX rate. Distinct from the return-on-cost percentages (which ignore timing) and from FIRE's
 *  snapshot-based figure (different data source). */
data class MoneyWeightedReturnDto(
    // Scale-4 fraction (0.1234 = +12.34 %): annualized rate, or the cumulative return
    // when the history spans less than a year. Null when unavailable — never a wrong number.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val value: BigDecimal?,
    // False → cumulative figure (history shorter than one year; annualizing it would mislead).
    val annualized: Boolean,
    // First trade date; null when there are no lots.
    val from: LocalDate?,
    // The as-of date the terminal value is taken at.
    val to: LocalDate,
    // Lot flows that went into the calculation, excluding the terminal flow.
    val flowCount: Int,
    // Current value of open holdings used as the closing inflow; null when unpriced.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val terminalValue: BigDecimal?,
    val unavailableReason: MoneyWeightedReturnUnavailableReason?,
)

data class PortfolioSummaryDto(
    val asOfDate: LocalDate,
    val holdings: List<HoldingSummaryDto>,
    // Remaining (FIFO-unconsumed) cost basis of open positions.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalCostBasis: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalValue: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalRealizedPnl: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalUnrealizedPnl: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalReturn: BigDecimal?,
    // FIFO cost basis of all sold lots, whole history.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalSoldCostBasis: BigDecimal,
    // Scale-4 fractions (0.1234 = +12.34 %); null when the denominator is zero or the numerator
    // is unknown. Denominators: open cost basis / sold cost basis / their sum (capital deployed).
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unrealizedPnlPct: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val realizedPnlPct: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalReturnPct: BigDecimal?,
    val moneyWeightedReturn: MoneyWeightedReturnDto,
    // Same metric scoped to each asset class's own lots and current value, so the
    // asset-type filter can show a class figure instead of a misleading portfolio-wide one.
    val moneyWeightedReturnByClass: Map<HoldingAssetClass, MoneyWeightedReturnDto>,
    val byClass: Map<String, BigDecimal>,
    val anyStale: Boolean,
    val anyUnpriced: Boolean,
)

data class HoldingValuationRowDto(
    val holdingId: UUID,
    val assetClass: HoldingAssetClass,
    val symbol: String,
    val label: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val quantity: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unitPrice: BigDecimal?,
    val priceCurrency: String?,
    val priceAsOf: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val fxRate: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val valueBase: BigDecimal,
    val stale: Boolean,
)

data class PortfolioValuationDto(
    val date: LocalDate,
    // Snapshot asset-class code -> EUR total for classes that have holdings.
    val byClass: Map<String, BigDecimal>,
    val holdings: List<HoldingValuationRowDto>,
    val anyStale: Boolean,
)

data class PortfolioEvolutionPointDto(
    val date: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val value: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val invested: BigDecimal,
    // Lifetime realized P&L of the filtered holdings up to this date.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val realizedPnl: BigDecimal,
    // value − remaining cost basis, summed over holdings priced at this date.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val unrealizedPnl: BigDecimal,
    // Cumulative time-weighted return since the range start, as a scale-4 fraction
    // (0.1234 = +12.34 %). Contribution/withdrawal timing is excluded; 0 at the start.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val twrPct: BigDecimal,
)

data class PortfolioEvolutionDto(
    val points: List<PortfolioEvolutionPointDto>,
)

/** Result of a user-triggered price refresh: false when skipped by the cooldown. */
data class PriceRefreshTriggerDto(
    val started: Boolean,
)
