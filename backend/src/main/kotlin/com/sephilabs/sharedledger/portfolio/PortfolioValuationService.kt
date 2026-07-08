package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

@Service
class PortfolioValuationService(
    private val holdings: HoldingRepository,
    private val lots: HoldingLotRepository,
    private val prices: PricePointRepository,
    private val fxRates: FxRateRepository,
    private val holdingService: HoldingService,
    private val valuations: HoldingValuationRepository,
    private val props: AppProperties,
) {

    private val baseCurrency: String get() = props.portfolio.baseCurrency
    private val costMethod get() = props.portfolio.costMethod

    @Transactional(readOnly = true)
    fun summary(householdId: UUID): PortfolioSummaryDto {
        val today = LocalDate.now()
        val all = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId)
        val lotsByHolding = lotsByHolding(all)

        data class Row(
            val holding: Holding,
            val price: PortfolioValuationCalculator.PriceInput?,
            val result: PortfolioValuationCalculator.HoldingValuationResult,
        )

        val results = all.map { holding ->
            val entries = (lotsByHolding[holding.id] ?: emptyList()).map { it.toEntry() }
            val state = PortfolioValuationCalculator.replay(entries, costMethod)
            val price = priceFor(holding, today)
            Row(
                holding, price,
                PortfolioValuationCalculator.value(state, price, today, props.portfolio.stalePriceThresholdDays),
            )
        }

        val pricedValues = results
            .mapNotNull { row -> row.result.currentValueBase?.let { row.holding.id to it } }
            .toMap()
        val weights = PortfolioValuationCalculator.weights(pricedValues)

        val holdingDtos = results.map { row ->
            HoldingSummaryDto(
                holding = withLotUnrealized(holdingService.toDto(row.holding), row.price),
                currentPrice = row.price?.price,
                priceCurrency = row.price?.currency,
                priceAsOf = row.result.priceAsOf,
                stale = row.result.stale,
                currentValue = row.result.currentValueBase,
                unrealizedPnl = row.result.unrealizedPnlAbs,
                unrealizedPnlPct = row.result.unrealizedPnlPct,
                realizedPnl = row.result.realizedPnlBase,
                totalReturn = row.result.totalReturnBase,
                weight = weights[row.holding.id],
            )
        }

        val totalCostBasis = Money.normalize(results.fold(BigDecimal.ZERO) { acc, row -> acc + row.result.remainingCostBasisBase })
        val totalValue = Money.normalize(pricedValues.values.fold(BigDecimal.ZERO, BigDecimal::add))
        val totalRealized = Money.normalize(results.fold(BigDecimal.ZERO) { acc, row -> acc + row.result.realizedPnlBase })
        val anyUnpriced = results.any { it.result.currentValueBase == null }
        val totalUnrealized =
            if (results.any { it.result.unrealizedPnlAbs != null }) {
                Money.normalize(results.fold(BigDecimal.ZERO) { acc, row -> acc + (row.result.unrealizedPnlAbs ?: BigDecimal.ZERO) })
            } else {
                null
            }

        val byClass = results
            .groupBy { ASSET_CLASS_TO_SNAPSHOT_CODE.getValue(it.holding.assetClass) }
            .mapValues { (_, rows) ->
                Money.normalize(rows.fold(BigDecimal.ZERO) { acc, row -> acc + (row.result.currentValueBase ?: BigDecimal.ZERO) })
            }

        return PortfolioSummaryDto(
            asOfDate = today,
            holdings = holdingDtos,
            totalCostBasis = totalCostBasis,
            totalValue = totalValue,
            totalRealizedPnl = totalRealized,
            totalUnrealizedPnl = totalUnrealized,
            totalReturn = totalUnrealized?.let { Money.normalize(totalRealized.add(it)) },
            byClass = byClass,
            anyStale = results.any { it.result.stale },
            anyUnpriced = anyUnpriced,
        )
    }

    /**
     * Values the household portfolio at an arbitrary date: the ledger is replayed up to
     * that date (net quantity is date-dependent) and priced with the last stored price
     * and FX rate <= that date (forward-fill). Used for snapshot prefill and freezing.
     */
    @Transactional(readOnly = true)
    fun valuationAt(householdId: UUID, date: LocalDate): PortfolioValuationDto {
        val all = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId).filter { it.active }
        val lotsByHolding = lotsByHolding(all)

        val rows = all.mapNotNull { holding ->
            val entries = (lotsByHolding[holding.id] ?: emptyList())
                .map { it.toEntry() }
                .filter { !it.tradedOn.isAfter(date) }
            if (entries.isEmpty()) return@mapNotNull null
            val netQty = PortfolioValuationCalculator.replay(entries, costMethod, upTo = date).netQuantity
            // Positions fully closed by this date hold nothing worth freezing.
            if (netQty.signum() == 0) return@mapNotNull null
            val price = priceFor(holding, date)
            val value =
                if (price == null) BigDecimal.ZERO.setScale(Money.SCALE)
                else Money.normalize(
                    netQty.multiply(price.price, MathContext.DECIMAL64)
                        .multiply(price.fxToBase, MathContext.DECIMAL64)
                )
            HoldingValuationRowDto(
                holdingId = holding.id,
                assetClass = holding.assetClass,
                symbol = holding.symbol,
                label = holding.label,
                quantity = netQty,
                unitPrice = price?.price,
                priceCurrency = price?.currency,
                priceAsOf = price?.priceDate,
                fxRate = price?.fxToBase,
                valueBase = value,
                stale = price == null || price.priceDate.isBefore(date.minusDays(props.portfolio.stalePriceThresholdDays)),
            )
        }

        val byClass = rows
            .groupBy { ASSET_CLASS_TO_SNAPSHOT_CODE.getValue(it.assetClass) }
            .mapValues { (_, classRows) ->
                Money.normalize(classRows.fold(BigDecimal.ZERO) { acc, r -> acc + r.valueBase })
            }

        return PortfolioValuationDto(
            date = date,
            byClass = byClass,
            holdings = rows,
            anyStale = rows.any { it.stale },
        )
    }

    @Transactional(readOnly = true)
    fun evolution(
        householdId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        assetClass: HoldingAssetClass? = null,
        holdingId: UUID? = null,
    ): PortfolioEvolutionDto {
        val all = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId)
            .filter { it.active }
            .filter { assetClass == null || it.assetClass == assetClass }
            .filter { holdingId == null || it.id == holdingId }
        val lotsByHolding = lotsByHolding(all)
        val allLots = lotsByHolding.values.flatten()
        if (allLots.isEmpty()) return PortfolioEvolutionDto(emptyList())

        val end = to ?: LocalDate.now()
        val start = from ?: allLots.minOf { it.tradedOn }
        if (start.isAfter(end)) return PortfolioEvolutionDto(emptyList())

        // Adaptive sampling keeps long ("all time") ranges light: daily up to
        // MAX_EVOLUTION_POINTS, then every stepDays. The end date is always included.
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        val stepDays = maxOf(1L, (totalDays + MAX_EVOLUTION_POINTS - 1) / MAX_EVOLUTION_POINTS)

        // One in-memory forward-fill cursor per holding and per foreign currency.
        val priceSeries = all.filter { it.linked }.associate { holding ->
            val currency = priceCurrencyFor(holding)
            val head = prices.findFirstByProviderAndProviderSymbolAndCurrencyAndPriceDateLessThanEqualOrderByPriceDateDesc(
                holding.provider!!.name, holding.providerSymbol!!, currency, start.minusDays(1),
            )
            val range = prices.findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
                holding.provider!!.name, holding.providerSymbol!!, currency, start, end,
            )
            holding.id to ForwardFill(
                (listOfNotNull(head) + range).map { it.priceDate to it.price },
            )
        }
        val fxSeries = all
            .map { priceCurrencyFor(it) }
            .filter { it != baseCurrency }
            .distinct()
            .associateWith { currency -> fxForwardFill(currency, start, end) }

        val entriesByHolding = all.associate { holding ->
            holding.id to (lotsByHolding[holding.id] ?: emptyList()).map { it.toEntry() }
        }

        val sampleDates = generateSequence(start) { it.plusDays(stepDays) }
            .takeWhile { !it.isAfter(end) }
            .toMutableList()
        if (sampleDates.lastOrNull() != end) sampleDates.add(end)

        // Running TWR state chained across the sample dates (see the per-point comment).
        var prevValue: BigDecimal? = null
        var prevCumFlow = BigDecimal.ZERO
        var twrFactor = BigDecimal.ONE

        val points = sampleDates
            .map { date ->
                var value = BigDecimal.ZERO
                var invested = BigDecimal.ZERO
                var realized = BigDecimal.ZERO
                var unrealized = BigDecimal.ZERO
                // Net external cash flow into the filtered portfolio up to this date
                // (BUY = +cost, SELL = −proceeds, in base). Assumes priced holdings, in
                // line with `value`; TWR chains the same daily valuation below.
                var cumFlow = BigDecimal.ZERO
                for (holding in all) {
                    val entries = entriesByHolding.getValue(holding.id).filter { !it.tradedOn.isAfter(date) }
                    if (entries.isEmpty()) continue
                    cumFlow = entries.fold(cumFlow) { acc, e -> acc.add(cashFlowBase(e), MathContext.DECIMAL64) }
                    val state = PortfolioValuationCalculator.replay(entries, costMethod, upTo = date)
                    // "Invested" is the remaining cost basis, so it steps down on sells.
                    invested += state.remainingCostBasisBase
                    realized += state.realizedPnlBase
                    if (state.netQuantity.signum() == 0) continue
                    val price = priceSeries[holding.id]?.at(date) ?: continue
                    val currency = priceCurrencyFor(holding)
                    val fx = if (currency == baseCurrency) BigDecimal.ONE else fxSeries[currency]?.at(date) ?: continue
                    val holdingValue = state.netQuantity.multiply(price, MathContext.DECIMAL64)
                        .multiply(fx, MathContext.DECIMAL64)
                    value += holdingValue
                    // Only priced holdings contribute: unpriced ones would fake a full loss.
                    unrealized += holdingValue.subtract(state.remainingCostBasisBase, MathContext.DECIMAL64)
                }

                // Chain this segment's return with the cash flow since the previous sample
                // excluded: r = (Vₑ − F) / Vₛ. The base re-anchors whenever the prior value
                // is ≤ 0 — an unfunded range start, or a position fully sold then re-bought.
                val segmentFlow = cumFlow.subtract(prevCumFlow, MathContext.DECIMAL64)
                val prev = prevValue
                if (prev != null && prev.signum() > 0) {
                    val factor = value.subtract(segmentFlow, MathContext.DECIMAL64)
                        .divide(prev, MathContext.DECIMAL64)
                    if (factor.signum() > 0) twrFactor = twrFactor.multiply(factor, MathContext.DECIMAL64)
                }
                prevValue = value
                prevCumFlow = cumFlow

                PortfolioEvolutionPointDto(
                    date,
                    Money.normalize(value),
                    Money.normalize(invested),
                    Money.normalize(realized),
                    Money.normalize(unrealized),
                    twrFactor.subtract(BigDecimal.ONE)
                        .setScale(PortfolioValuationCalculator.FRACTION_SCALE, RoundingMode.HALF_EVEN),
                )
            }

        return PortfolioEvolutionDto(points)
    }

    /** Freezes one valuation row per active holding with an open position at the snapshot date. */
    @Transactional
    fun freezeValuations(snapshotId: UUID, householdId: UUID, date: LocalDate): List<HoldingValuation> {
        valuations.deleteAllBySnapshotId(snapshotId)
        val rows = valuationAt(householdId, date).holdings.map {
            HoldingValuation(
                snapshotId = snapshotId,
                holdingId = it.holdingId,
                quantity = it.quantity,
                unitPrice = it.unitPrice,
                priceCurrency = it.priceCurrency,
                priceAsOf = it.priceAsOf,
                fxRate = it.fxRate,
                valueBase = it.valueBase,
                stale = it.stale,
            )
        }
        return valuations.saveAll(rows)
    }

    /** Currency the provider quotes this holding in: crypto is requested directly in base. */
    fun priceCurrencyFor(holding: Holding): String =
        if (holding.assetClass == HoldingAssetClass.crypto) baseCurrency else holding.nativeCurrency

    /** Last stored price <= date plus the FX rate into base; null when unpriced (unlinked, no data, no FX). */
    fun priceFor(holding: Holding, date: LocalDate): PortfolioValuationCalculator.PriceInput? {
        if (!holding.linked) return null
        val currency = priceCurrencyFor(holding)
        val point = prices.findFirstByProviderAndProviderSymbolAndCurrencyAndPriceDateLessThanEqualOrderByPriceDateDesc(
            holding.provider!!.name, holding.providerSymbol!!, currency, date,
        ) ?: return null
        val fx = if (currency == baseCurrency) {
            BigDecimal.ONE
        } else {
            fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                currency, baseCurrency, date,
            )?.rate ?: return null
        }
        return PortfolioValuationCalculator.PriceInput(point.price, point.priceDate, currency, fx)
    }

    /**
     * Fills each BUY lot's unrealized P&L = remaining quantity × current price (in base) −
     * remaining cost basis. Left null when the holding is unpriced. Summed over the BUY lots
     * these reconcile with the holding-level unrealized P&L (netQuantity × price − cost basis).
     */
    private fun withLotUnrealized(dto: HoldingDto, price: PortfolioValuationCalculator.PriceInput?): HoldingDto {
        if (price == null) return dto
        val perUnitBase = price.price.multiply(price.fxToBase, MathContext.DECIMAL64)
        val lots = dto.lots.map { lot ->
            val remainingQty = lot.remainingQty
            val remainingCostBasis = lot.remainingCostBasis
            if (lot.type != LotType.BUY || remainingQty == null || remainingCostBasis == null) {
                lot
            } else {
                val unrealized = Money.normalize(
                    remainingQty.multiply(perUnitBase, MathContext.DECIMAL64).subtract(remainingCostBasis, MathContext.DECIMAL64)
                )
                lot.copy(unrealizedPnl = unrealized)
            }
        }
        return dto.copy(lots = lots)
    }

    /** Signed base-currency cash flow of a ledger entry: +cost for a BUY, −proceeds for a SELL. */
    private fun cashFlowBase(e: PortfolioValuationCalculator.LedgerEntry): BigDecimal {
        val gross = e.quantity.multiply(e.unitPrice, MathContext.DECIMAL64)
        val net = when (e.type) {
            LotType.BUY -> gross.add(e.fee ?: BigDecimal.ZERO, MathContext.DECIMAL64)
            LotType.SELL -> gross.subtract(e.fee ?: BigDecimal.ZERO, MathContext.DECIMAL64)
        }
        val base = net.multiply(e.fxRateToBase, MathContext.DECIMAL64)
        return if (e.type == LotType.BUY) base else base.negate()
    }

    private fun lotsByHolding(all: List<Holding>): Map<UUID, List<HoldingLot>> =
        if (all.isEmpty()) emptyMap()
        else lots.findAllByHoldingIdIn(all.map { it.id }).groupBy { it.holdingId }

    private fun fxForwardFill(currency: String, start: LocalDate, end: LocalDate): ForwardFill {
        val head = fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            currency, baseCurrency, start.minusDays(1),
        )
        val range = fxRates.findAllByBaseCurrencyAndQuoteCurrencyAndRateDateBetweenOrderByRateDateAsc(
            currency, baseCurrency, start, end,
        )
        return ForwardFill((listOfNotNull(head) + range).map { it.rateDate to it.rate })
    }

    /** Sorted (date, value) observations; at(d) returns the last value <= d. */
    private class ForwardFill(private val points: List<Pair<LocalDate, BigDecimal>>) {
        fun at(date: LocalDate): BigDecimal? =
            points.lastOrNull { !it.first.isAfter(date) }?.second
    }

    companion object {
        // Evolution charts serve at most this many points; longer ranges sample coarser.
        const val MAX_EVOLUTION_POINTS: Long = 800
    }
}
