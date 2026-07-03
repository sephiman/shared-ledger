package com.sephilabs.sharedledger.portfolio.price

import java.math.BigDecimal
import java.time.LocalDate

/** A provider search hit the user can pick to link a holding. */
data class SymbolCandidate(
    val provider: String,
    val providerSymbol: String,
    val name: String,
    val symbol: String? = null,
    val currency: String? = null,
    val exchange: String? = null,
    val isin: String? = null,
)

data class DailyPrice(val date: LocalDate, val price: BigDecimal)

/**
 * Equity history plus the currency the provider says the instrument trades in.
 * Yahoo's search results carry no currency, so the chart's meta.currency is the
 * source of truth; null when the provider does not report one (EODHD).
 */
data class EquityHistory(val currency: String?, val prices: List<DailyPrice>)

class ProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface CryptoPriceProvider {
    fun search(query: String): List<SymbolCandidate>

    /** Current prices for many coins in one call, quoted in [vsCurrency]. */
    fun currentPrices(ids: List<String>, vsCurrency: String): Map<String, BigDecimal>

    fun dailyHistory(id: String, vsCurrency: String, from: LocalDate, to: LocalDate): List<DailyPrice>
}

interface EquityPriceProvider {
    fun search(query: String): List<SymbolCandidate>

    fun searchByIsin(isin: String): List<SymbolCandidate>

    fun dailyHistory(symbol: String, from: LocalDate, to: LocalDate): EquityHistory
}

interface FxRateProvider {
    /** Daily reference rates converting [base] into [quote], business days only. */
    fun history(base: String, quote: String, from: LocalDate, to: LocalDate): List<DailyPrice>
}

/**
 * Secondary crypto history source used when the primary can't serve a range
 * (CoinGecko's 365-day Demo ceiling) or is unavailable. Quotes are USD(T) closes
 * for a Binance-style pair (BTCUSDT); the caller converts to the base currency.
 * An unknown pair returns an empty list, never an error.
 */
interface CryptoHistoryFallback {
    fun dailyHistoryUsd(pair: String, from: LocalDate, to: LocalDate): List<DailyPrice>
}
