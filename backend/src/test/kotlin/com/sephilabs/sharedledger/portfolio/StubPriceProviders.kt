package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.portfolio.price.CryptoHistoryFallback
import com.sephilabs.sharedledger.portfolio.price.CryptoPriceProvider
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.EquityHistory
import com.sephilabs.sharedledger.portfolio.price.EquityPriceProvider
import com.sephilabs.sharedledger.portfolio.price.FxRateProvider
import com.sephilabs.sharedledger.portfolio.price.ProviderException
import com.sephilabs.sharedledger.portfolio.price.RateLimitedException
import com.sephilabs.sharedledger.portfolio.price.SymbolCandidate
import com.sephilabs.sharedledger.portfolio.price.SymbolNotFoundException
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.math.BigDecimal
import java.time.LocalDate

/** Controllable in-memory provider stubs, so no portfolio integration test performs real HTTP. Seed the
 *  maps, then exercise the services. */
class StubCryptoProvider : CryptoPriceProvider {
    val history = mutableMapOf<String, MutableList<DailyPrice>>()
    val current = mutableMapOf<String, BigDecimal>()
    val failFor = mutableSetOf<String>()
    val historyCalls = mutableListOf<Triple<String, LocalDate, LocalDate>>()
    var searchResults = listOf<SymbolCandidate>()

    override fun search(query: String): List<SymbolCandidate> = searchResults

    override fun currentPrices(ids: List<String>, vsCurrency: String): Map<String, BigDecimal> =
        current.filterKeys { it in ids }

    override fun dailyHistory(id: String, vsCurrency: String, from: LocalDate, to: LocalDate): List<DailyPrice> {
        if (id in failFor) throw ProviderException("stubbed failure for $id")
        historyCalls += Triple(id, from, to)
        return history[id]?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) } ?: emptyList()
    }
}

class StubEquityProvider : EquityPriceProvider {
    val history = mutableMapOf<String, MutableList<DailyPrice>>()
    // Listing currency reported with the history (Yahoo chart meta); null when unset.
    val currencies = mutableMapOf<String, String>()
    val failFor = mutableSetOf<String>()
    // Symbols the provider answers 404 for (delisted); the call is still recorded.
    val notFoundFor = mutableSetOf<String>()
    // Symbols the provider answers 429 for; the call is still recorded.
    val rateLimitedFor = mutableSetOf<String>()
    // Runs inside every history call, e.g. to act while a refresh is in flight.
    var duringHistory: () -> Unit = {}
    val historyCalls = mutableListOf<Triple<String, LocalDate, LocalDate>>()
    var searchResults = listOf<SymbolCandidate>()
    var isinResults = mapOf<String, List<SymbolCandidate>>()
    var searchFails = false

    override fun search(query: String): List<SymbolCandidate> {
        if (searchFails) throw ProviderException("stubbed search failure")
        return searchResults
    }

    override fun searchByIsin(isin: String): List<SymbolCandidate> {
        if (searchFails) throw ProviderException("stubbed search failure")
        return isinResults[isin] ?: emptyList()
    }

    override fun dailyHistory(symbol: String, from: LocalDate, to: LocalDate): EquityHistory {
        if (symbol in failFor) throw ProviderException("stubbed failure for $symbol")
        historyCalls += Triple(symbol, from, to)
        duringHistory()
        if (symbol in notFoundFor) throw SymbolNotFoundException(symbol, "stubbed 404 for $symbol")
        if (symbol in rateLimitedFor) throw RateLimitedException("yahoo", "stubbed 429 for $symbol")
        val prices = history[symbol]?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) } ?: emptyList()
        return EquityHistory(currencies[symbol], prices)
    }
}

class StubFxProvider : FxRateProvider {
    val history = mutableMapOf<String, MutableList<DailyPrice>>()
    val failFor = mutableSetOf<String>()

    override fun history(base: String, quote: String, from: LocalDate, to: LocalDate): List<DailyPrice> {
        if (base in failFor) throw ProviderException("stubbed failure for $base")
        return history[base]?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) } ?: emptyList()
    }
}

class StubCryptoFallback : CryptoHistoryFallback {
    // Keyed by Binance pair (BTCUSDT); values are USD(T) closes.
    val history = mutableMapOf<String, MutableList<DailyPrice>>()
    val calls = mutableListOf<Triple<String, LocalDate, LocalDate>>()

    override fun dailyHistoryUsd(pair: String, from: LocalDate, to: LocalDate): List<DailyPrice> {
        calls += Triple(pair, from, to)
        return history[pair]?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) } ?: emptyList()
    }
}

@TestConfiguration
class StubPriceProviderConfig {
    @Bean
    @Primary
    fun stubCryptoProvider(): StubCryptoProvider = StubCryptoProvider()

    @Bean
    @Primary
    fun stubEquityProvider(): StubEquityProvider = StubEquityProvider()

    @Bean
    @Primary
    fun stubFxProvider(): StubFxProvider = StubFxProvider()

    @Bean
    @Primary
    fun stubCryptoFallback(): StubCryptoFallback = StubCryptoFallback()
}
