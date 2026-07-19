package com.sephilabs.sharedledger.portfolio.benchmark

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.PortfolioValuationCalculator
import com.sephilabs.sharedledger.portfolio.PortfolioValuationService
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Reads stored benchmark closes and turns them into a normalized time-weighted-return
 * line comparable to the portfolio ROI (TWR) chart: EUR terms (each date's close converted
 * with the ECB rate <= that date), 0 % at the window's anchor, on the exact same sample
 * dates the user's TWR curve uses. No live provider calls — the background refresh job owns
 * fetching. A price-only series has no cash flows, so its TWR is simply EUR(t)/EUR(anchor)−1.
 */
@Service
class BenchmarkService(
    private val valuation: PortfolioValuationService,
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
    private val fxRates: FxRateRepository,
    private val props: AppProperties,
) {

    private val baseCurrency: String get() = props.portfolio.baseCurrency

    /** The enabled benchmarks plus the extent of their stored data, for the selector UI. */
    @Transactional(readOnly = true)
    fun list(): List<BenchmarkDto> = benchmarks.findAllByEnabledTrueOrderBySortOrderAsc().map { b ->
        val from = prices.findMinPriceDate(b.key)
        val to = prices.findMaxPriceDate(b.key)
        BenchmarkDto(
            key = b.key,
            currency = b.currency,
            kind = b.kind,
            hasData = to != null,
            availableFrom = from,
            availableTo = to,
        )
    }

    /**
     * Normalized TWR series for the requested (enabled) benchmarks over the same window the
     * TWR curve covers for these filters. Empty when there is no curve (no lots / empty window).
     */
    @Transactional(readOnly = true)
    fun series(
        householdId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        assetClass: HoldingAssetClass?,
        holdingId: UUID?,
        keys: List<String>,
    ): BenchmarkSeriesResponseDto {
        val sampleDates = valuation.sampleWindow(householdId, from, to, assetClass, holdingId)
            ?: return BenchmarkSeriesResponseDto(emptyList())
        val requested = benchmarks.findAllByEnabledTrueOrderBySortOrderAsc()
            .filter { keys.isEmpty() || it.key in keys }
        return BenchmarkSeriesResponseDto(requested.map { buildSeries(it, sampleDates) })
    }

    private fun buildSeries(benchmark: Benchmark, sampleDates: List<LocalDate>): BenchmarkSeriesDto {
        val start = sampleDates.first()
        val end = sampleDates.last()

        // Forward-fill closes (in the benchmark's currency) and, when foreign, the ECB rate.
        val head = prices.findFirstByBenchmarkKeyAndPriceDateLessThanEqualOrderByPriceDateDesc(
            benchmark.key, start.minusDays(1),
        )
        val range = prices.findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(benchmark.key, start, end)
        val closeFill = ForwardFill((listOfNotNull(head) + range).map { it.priceDate to it.close })
        val fxFill = if (benchmark.currency == baseCurrency) null else fxForwardFill(benchmark.currency, start, end)

        // EUR close per sample date; null when either the close or (for foreign) the rate is missing.
        val eurByDate: Map<LocalDate, BigDecimal?> = sampleDates.associateWith { date ->
            val close = closeFill.at(date) ?: return@associateWith null
            if (benchmark.currency == baseCurrency) {
                close
            } else {
                val fx = fxFill?.at(date) ?: return@associateWith null
                close.multiply(fx, MathContext.DECIMAL64)
            }
        }

        val firstAvailable = sampleDates.firstOrNull { eurByDate[it] != null }
        if (firstAvailable == null) {
            // Nothing stored anywhere in the window: all-gap line, flagged partial.
            return BenchmarkSeriesDto(
                key = benchmark.key,
                currency = benchmark.currency,
                points = sampleDates.map { BenchmarkSeriesPointDto(it, null) },
                availableFrom = null,
                availableTo = null,
                partial = true,
            )
        }

        val anchor = eurByDate.getValue(firstAvailable)!!
        val points = sampleDates.map { date ->
            val eur = eurByDate[date]
            val twr = eur?.divide(anchor, MathContext.DECIMAL64)
                ?.subtract(BigDecimal.ONE)
                ?.setScale(PortfolioValuationCalculator.FRACTION_SCALE, RoundingMode.HALF_EVEN)
            BenchmarkSeriesPointDto(date, twr)
        }
        val lastAvailable = sampleDates.lastOrNull { eurByDate[it] != null }
        // Partial when the covered sub-range does not span the whole window (leading or interior gap).
        val partial = firstAvailable != start || points.any { it.twrPct == null }

        return BenchmarkSeriesDto(
            key = benchmark.key,
            currency = benchmark.currency,
            points = points,
            availableFrom = firstAvailable,
            availableTo = lastAvailable,
            partial = partial,
        )
    }

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
}
