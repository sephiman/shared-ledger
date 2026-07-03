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
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalReturn: BigDecimal?,
    // Fraction of the priced portfolio value, scale 4.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val weight: BigDecimal?,
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
)

data class PortfolioEvolutionDto(
    val points: List<PortfolioEvolutionPointDto>,
)
