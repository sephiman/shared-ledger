package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.config.AppProperties.CostMethod
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Pure portfolio math over the BUY/SELL movement ledger. Net quantity, remaining cost
 * basis and realized P&L come from replaying the ledger with the configured cost
 * method (FIFO) — nothing is stored denormalized. Intermediate arithmetic uses
 * DECIMAL64; money results are normalized only at the boundary.
 */
object PortfolioValuationCalculator {
    private val MC = MathContext.DECIMAL64
    const val FRACTION_SCALE: Int = 4

    data class LedgerEntry(
        val type: LotType,
        val tradedOn: LocalDate,
        val quantity: BigDecimal,
        val unitPrice: BigDecimal,
        val fee: BigDecimal?,
        val fxRateToBase: BigDecimal,
    )

    data class LedgerState(
        val netQuantity: BigDecimal,
        val remainingCostBasisBase: BigDecimal,
        val realizedPnlBase: BigDecimal,
    )

    data class PriceInput(
        val price: BigDecimal,
        val priceDate: LocalDate,
        val currency: String,
        // FX from the price currency into the household base currency; 1 when already base.
        val fxToBase: BigDecimal,
    )

    data class HoldingValuationResult(
        val netQuantity: BigDecimal,
        val remainingCostBasisBase: BigDecimal,
        val realizedPnlBase: BigDecimal,
        val currentValueBase: BigDecimal?,      // null when unpriced; 0.00 for closed positions
        val unrealizedPnlAbs: BigDecimal?,
        val unrealizedPnlPct: BigDecimal?,
        val totalReturnBase: BigDecimal?,       // realized + unrealized
        val priceAsOf: LocalDate?,
        val stale: Boolean,
    )

    class OversellException(val tradedOn: LocalDate) :
        RuntimeException("SELL exceeds quantity held on $tradedOn")

    private class OpenLot(var remainingQty: BigDecimal, val perUnitCostBase: BigDecimal)

    /**
     * Replays the ledger up to (and including) [upTo]. Entries are processed in
     * trade-date order, BUYs before SELLs on the same date, insertion order otherwise.
     * With [strict] an oversell throws [OversellException] (write-time validation);
     * without it the uncovered remainder is consumed at zero cost so reads never fail.
     */
    fun replay(
        entries: List<LedgerEntry>,
        method: CostMethod = CostMethod.FIFO,
        upTo: LocalDate? = null,
        strict: Boolean = false,
    ): LedgerState {
        when (method) {
            CostMethod.FIFO -> Unit
            CostMethod.AVERAGE -> throw UnsupportedOperationException("Cost method AVERAGE is not implemented yet")
        }
        val ordered = entries
            .filter { upTo == null || !it.tradedOn.isAfter(upTo) }
            .sortedWith(compareBy({ it.tradedOn }, { it.type != LotType.BUY }))

        val queue = ArrayDeque<OpenLot>()
        var netQuantity = BigDecimal.ZERO
        var realized = BigDecimal.ZERO

        for (entry in ordered) {
            when (entry.type) {
                LotType.BUY -> {
                    netQuantity = netQuantity.add(entry.quantity, MC)
                    // Per-unit base cost prorates the purchase fee over the lot.
                    val lotCost = entry.quantity.multiply(entry.unitPrice, MC)
                        .add(entry.fee ?: BigDecimal.ZERO, MC)
                        .multiply(entry.fxRateToBase, MC)
                    queue.addLast(OpenLot(entry.quantity, lotCost.divide(entry.quantity, MC)))
                }
                LotType.SELL -> {
                    netQuantity = netQuantity.subtract(entry.quantity, MC)
                    val proceeds = entry.quantity.multiply(entry.unitPrice, MC)
                        .subtract(entry.fee ?: BigDecimal.ZERO, MC)
                        .multiply(entry.fxRateToBase, MC)
                    var toConsume = entry.quantity
                    var costOfSold = BigDecimal.ZERO
                    while (toConsume.signum() > 0 && queue.isNotEmpty()) {
                        val front = queue.first()
                        val take = front.remainingQty.min(toConsume)
                        costOfSold = costOfSold.add(take.multiply(front.perUnitCostBase, MC), MC)
                        front.remainingQty = front.remainingQty.subtract(take, MC)
                        toConsume = toConsume.subtract(take, MC)
                        if (front.remainingQty.signum() <= 0) queue.removeFirst()
                    }
                    if (toConsume.signum() > 0 && strict) throw OversellException(entry.tradedOn)
                    realized = realized.add(proceeds.subtract(costOfSold, MC), MC)
                }
            }
        }

        val remainingCost = queue.fold(BigDecimal.ZERO) { acc, lot ->
            acc.add(lot.remainingQty.multiply(lot.perUnitCostBase, MC), MC)
        }
        return LedgerState(
            netQuantity = netQuantity,
            remainingCostBasisBase = Money.normalize(remainingCost),
            realizedPnlBase = Money.normalize(realized),
        )
    }

    fun netQuantityAt(entries: List<LedgerEntry>, date: LocalDate, method: CostMethod = CostMethod.FIFO): BigDecimal =
        replay(entries, method, upTo = date).netQuantity

    fun value(
        state: LedgerState,
        price: PriceInput?,
        asOfDate: LocalDate,
        staleThresholdDays: Long,
    ): HoldingValuationResult {
        val closed = state.netQuantity.signum() == 0
        if (price == null && !closed) {
            return HoldingValuationResult(
                netQuantity = state.netQuantity,
                remainingCostBasisBase = state.remainingCostBasisBase,
                realizedPnlBase = state.realizedPnlBase,
                currentValueBase = null,
                unrealizedPnlAbs = null,
                unrealizedPnlPct = null,
                totalReturnBase = null,
                priceAsOf = null,
                stale = true,
            )
        }
        val value =
            if (closed) BigDecimal.ZERO.setScale(Money.SCALE)
            else Money.normalize(state.netQuantity.multiply(price!!.price, MC).multiply(price.fxToBase, MC))
        val unrealized = value.subtract(state.remainingCostBasisBase)
        val unrealizedPct =
            if (state.remainingCostBasisBase.signum() == 0) null
            else unrealized.divide(state.remainingCostBasisBase, FRACTION_SCALE, RoundingMode.HALF_EVEN)
        val stale = !closed && price!!.priceDate.isBefore(asOfDate.minusDays(staleThresholdDays))
        return HoldingValuationResult(
            netQuantity = state.netQuantity,
            remainingCostBasisBase = state.remainingCostBasisBase,
            realizedPnlBase = state.realizedPnlBase,
            currentValueBase = value,
            unrealizedPnlAbs = unrealized,
            unrealizedPnlPct = unrealizedPct,
            totalReturnBase = state.realizedPnlBase.add(unrealized),
            priceAsOf = if (closed) price?.priceDate else price!!.priceDate,
            stale = stale,
        )
    }

    /** Weight of each value within the total, as a scale-4 fraction. Zero total -> empty. */
    fun weights(valuesById: Map<java.util.UUID, BigDecimal>): Map<java.util.UUID, BigDecimal> {
        val total = valuesById.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() == 0) return emptyMap()
        return valuesById.mapValues { (_, v) -> v.divide(total, FRACTION_SCALE, RoundingMode.HALF_EVEN) }
    }
}
