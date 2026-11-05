package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingLot
import com.sephilabs.sharedledger.portfolio.HoldingLotRepository
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import com.sephilabs.sharedledger.portfolio.PortfolioValuationCalculator
import com.sephilabs.sharedledger.portfolio.toEntry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Gap-fill and backfill engine for price_history and fx_rates. Idempotent: rows are upserted by their
 *  unique (provider coordinates, date) key, so overlapping runs and re-runs after failures self-heal. */
@Service
class PriceRefreshService(
    private val holdings: HoldingRepository,
    private val lots: HoldingLotRepository,
    private val prices: PricePointRepository,
    private val fxRates: FxRateRepository,
    private val crypto: CryptoPriceProvider,
    private val cryptoFallback: CryptoHistoryFallback,
    private val equity: EquityPriceProvider,
    private val fx: FxRateProvider,
    private val metrics: AppMetrics,
    private val props: AppProperties,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(PriceRefreshService::class.java)

    private val baseCurrency: String get() = props.portfolio.baseCurrency
    private val vsCurrency: String get() = props.portfolio.vsCurrency

    /** Hourly: gap-fill missed days, then upsert today's row from one batched current-price call. */
    fun refreshCrypto(today: LocalDate, scope: ManualRefreshScope? = null) {
        val demands = demandsFor(HoldingProvider.coingecko, today, scope)
        if (demands.isEmpty()) return

        for (demand in demands) {
            val id = demand.providerSymbol
            try {
                gapFillCrypto(demand, today)
            } catch (ex: ProviderException) {
                abortIfRateLimited(ex, scope)
                metrics.priceRefreshFailure(CoinGeckoClient.PROVIDER)
                log.error("Crypto gap-fill failed for {}: {}; trying Binance fallback", id, ex.message)
                // Binance covers from the last stored day, or the earliest lot when CoinGecko
                // never populated the series at all.
                val maxStored = prices.findMaxPriceDate(CoinGeckoClient.PROVIDER, id, baseCurrency)
                if (maxStored == null || demand.needsTail(maxStored, today.minusDays(1))) {
                    val from = maxStored?.plusDays(1) ?: cryptoCeiling(demand.earliestLot ?: today, today)
                    fallbackCryptoFill(id, tickerOf(demand), from, demand.fetchUntil(today.minusDays(1)), today)
                }
            }
            pace(props.portfolio.coingecko.minRequestIntervalMs)
        }
        // A closed position's value is zero at any price, so only open coordinates need today's quote.
        val coords = demands.filter { it.open }.map { it.providerSymbol }
        if (coords.isEmpty()) return
        try {
            val current = crypto.currentPrices(coords, vsCurrency)
            val now = Instant.now()
            current.forEach { (id, price) ->
                upsertPrice(CoinGeckoClient.PROVIDER, id, baseCurrency, DailyPrice(today, price), now)
            }
            metrics.priceRefreshed(CoinGeckoClient.PROVIDER)
        } catch (ex: ProviderException) {
            abortIfRateLimited(ex, scope)
            metrics.priceRefreshFailure(CoinGeckoClient.PROVIDER)
            log.error("Crypto current-price refresh failed: {}", ex.message)
        }
    }

    /** Nightly, after FX: per distinct linked equity symbol, gap-fill EOD prices — to today while a holding is
     *  open, only up to the close once all are closed. Only holdings on the ACTIVE provider are refreshed; others
     *  keep their stored prices and show stale until relinked. A failing symbol never crashes the job, and one
     *  the provider no longer lists is re-checked weekly instead of nightly. */
    fun refreshEquities(today: LocalDate, scope: ManualRefreshScope? = null) {
        val active = props.portfolio.equityProvider.asHoldingProvider()
        for (demand in demandsFor(active, today, scope)) {
            if (inNotFoundBackoff(demand, today)) continue
            refreshEquityDemand(active, demand, today, scope)
            pace(activeEquityConfig().minRequestIntervalMs)
        }
    }

    /** Gap-fills the coordinate [holding] prices off, e.g. after a reopened position left its series ending at the
     *  old close. A user action, so it ignores the not-found back-off. */
    fun gapFillCoordinateOf(holding: Holding, today: LocalDate = LocalDate.now()) {
        val provider = holding.provider ?: return
        if (!props.portfolio.backfillOnLink) return
        val demand = allDemandsFor(provider)
            .firstOrNull { it.providerSymbol == holding.providerSymbol && it.currency == priceCurrencyOf(holding) }
            ?: return
        when (provider) {
            HoldingProvider.coingecko -> try {
                gapFillCrypto(demand, today)
            } catch (ex: ProviderException) {
                metrics.priceRefreshFailure(provider.name)
                log.error("Crypto gap-fill failed for {}: {}", demand.providerSymbol, ex.message)
            }
            HoldingProvider.yahoo, HoldingProvider.eodhd, HoldingProvider.twelvedata ->
                if (provider == props.portfolio.equityProvider.asHoldingProvider()) refreshEquityDemand(provider, demand, today)
        }
    }

    private fun refreshEquityDemand(
        provider: HoldingProvider,
        demand: CoordinateDemand,
        today: LocalDate,
        scope: ManualRefreshScope? = null,
    ) {
        try {
            if (refreshEquitySymbol(provider.name, demand, today)) {
                metrics.priceRefreshed(provider.name)
                clearNotFound(demand.holdings, demand.providerSymbol)
            }
        } catch (_: SymbolNotFoundException) {
            metrics.priceRefreshFailure(provider.name)
            log.warn("Equity refresh: {} is not listed by {}; re-checking weekly", demand.providerSymbol, provider)
            markNotFound(demand.holdings, demand.providerSymbol, today)
        } catch (ex: ProviderException) {
            abortIfRateLimited(ex, scope)
            metrics.priceRefreshFailure(provider.name)
            log.error("Equity refresh failed for {}: {}", demand.providerSymbol, ex.message)
        }
    }

    /** Fills an equity series both ways so a failed request-time backfill self-heals: bootstraps the
     *  ceiling-clamped range, extends the head to the earliest lot, tails to today (or just past the close of a
     *  fully closed coordinate, after which nothing is fetched). True if anything was fetched. */
    private fun refreshEquitySymbol(provider: String, demand: CoordinateDemand, today: LocalDate): Boolean {
        val symbol = demand.providerSymbol
        val currency = demand.currency
        val group = demand.holdings
        val minStored = prices.findMinPriceDate(provider, symbol, currency)
        val maxStored = prices.findMaxPriceDate(provider, symbol, currency)
        val earliestLot = demand.earliestLot ?: today
        // Starts a few days early so a lot dated on a market holiday still gets a prior close and the gap closes.
        val headFrom = clampToEquityCeiling(earliestLot.minusDays(EQUITY_HEAD_MARGIN_DAYS), today)
        val until = demand.fetchUntil(today)
        if (minStored == null || maxStored == null) {
            return fetchEquityRange(provider, symbol, group, currency, headFrom, until, today, isHead = true)
        }
        var fetched = false
        if (clampToEquityCeiling(earliestLot, today).isBefore(minStored)) {
            log.info("Equity head gap-fill for {} [{}..{}]", symbol, headFrom, minStored.minusDays(1))
            fetched = fetchEquityRange(provider, symbol, group, currency, headFrom, minStored.minusDays(1), today, isHead = true)
            pace(activeEquityConfig().minRequestIntervalMs)
        }
        val tailFrom = equityTailFrom(demand, maxStored, today) ?: return fetched
        return fetchEquityRange(provider, symbol, group, currency, tailFrom, until, today, isHead = false) || fetched
    }

    // An open series re-fetches its last stored day: a run during market hours stores a partial bar that only a
    // later run can settle to the close. A closed series only fills up to its close, as before.
    private fun equityTailFrom(demand: CoordinateDemand, maxStored: LocalDate, today: LocalDate): LocalDate? = when {
        demand.open -> clampToEquityCeiling(maxStored, today)
        demand.needsTail(maxStored, today) -> clampToEquityCeiling(maxStored.plusDays(1), today)
        else -> null
    }

    /** A head fetch also tops up the foreign-currency FX head so old-date valuations convert (the nightly FX
     *  job only tails). False when the range is empty. */
    private fun fetchEquityRange(
        provider: String,
        symbol: String,
        group: List<Holding>,
        expectedCurrency: String,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        isHead: Boolean,
    ): Boolean {
        if (from.isAfter(to)) return false
        val history = equity.dailyHistory(symbol, from, to)
        val effectiveCurrency = reconcileCurrency(group, expectedCurrency, history.currency, from, today)
        storeAll(provider, symbol, effectiveCurrency, history.prices)
        if (isHead && effectiveCurrency != baseCurrency) {
            runCatching { refreshFxCurrency(effectiveCurrency, today, earliestNeeded = from) }
        }
        return true
    }

    /** The provider's reported listing currency (Yahoo chart meta) is the source of truth: search results carry
     *  none, so native_currency can be wrong until the first fetch. On mismatch holdings are corrected and
     *  prices stored under the real currency — otherwise a USD listing would be valued as EUR with FX=1. */
    private fun reconcileCurrency(
        group: List<Holding>,
        expected: String,
        reported: String?,
        from: LocalDate,
        today: LocalDate,
    ): String {
        if (reported == null || reported == expected) return expected
        log.warn(
            "Provider reports currency {} for {} (holding said {}); correcting",
            reported, group.firstOrNull()?.providerSymbol, expected,
        )
        for (holding in group) {
            holding.nativeCurrency = reported
            holdings.save(holding)
        }
        if (reported != baseCurrency) {
            runCatching { refreshFxCurrency(reported, today, earliestNeeded = from) }
        }
        return reported
    }

    /** Daily, before equities: gap-fill FX for every foreign currency used by holdings or lots. */
    fun refreshFx(today: LocalDate, scope: ManualRefreshScope? = null) {
        val currencies = foreignCurrencies(scope)
        for (currency in currencies) {
            try {
                refreshFxCurrency(currency, today)
                metrics.priceRefreshed(FrankfurterClient.PROVIDER)
            } catch (ex: ProviderException) {
                metrics.priceRefreshFailure(FrankfurterClient.PROVIDER)
                log.error("FX refresh failed for {}: {}", currency, ex.message)
            }
            pace(props.portfolio.frankfurter.minRequestIntervalMs)
        }
    }

    /** FX history for one currency: tail gap-fill to [today], plus a head extension when [earliestNeeded]
     *  precedes the stored range. The head fetch starts a few days early so weekend dates forward-fill. */
    fun refreshFxCurrency(currency: String, today: LocalDate, earliestNeeded: LocalDate? = null) {
        val maxStored = fxRates.findMaxRateDate(currency, baseCurrency)
        if (maxStored == null) {
            val from = (earliestNeeded ?: earliestLotDateFor(currency) ?: today.minusDays(DEFAULT_FX_LOOKBACK_DAYS))
                .minusDays(FX_HEAD_MARGIN_DAYS)
            if (!from.isAfter(today)) {
                fx.history(currency, baseCurrency, from, today).forEach { upsertFx(currency, it) }
            }
            return
        }
        if (maxStored.isBefore(today)) {
            fx.history(currency, baseCurrency, maxStored.plusDays(1), today).forEach { upsertFx(currency, it) }
        }
        val minStored = fxRates.findMinRateDate(currency, baseCurrency)
        if (earliestNeeded != null && minStored != null && earliestNeeded.isBefore(minStored)) {
            fx.history(currency, baseCurrency, earliestNeeded.minusDays(FX_HEAD_MARGIN_DAYS), minStored.minusDays(1))
                .forEach { upsertFx(currency, it) }
        }
    }

    /** Ranged backfill for a freshly linked holding: from = max(earliest lot date, provider ceiling) to today.
     *  Ranged backfill for a freshly linked holding: from = max(earliest lot date, provider ceiling) to today. */
    fun backfillForHolding(holding: Holding, today: LocalDate = LocalDate.now()) {
        if (!holding.linked || !props.portfolio.backfillOnLink) return
        val earliestLot = lots.findMinTradedOn(holding.id) ?: today
        val from = clampToCeiling(holding, earliestLot, today)
        try {
            fetchRange(holding, from, today)
            if (priceCurrencyOf(holding) != baseCurrency) {
                refreshFxCurrency(priceCurrencyOf(holding), today, earliestNeeded = from)
            }
            // Lots older than the CoinGecko ceiling: Binance covers the pre-ceiling head.
            if (holding.provider == HoldingProvider.coingecko && earliestLot.isBefore(from)) {
                fallbackCryptoFill(holding.providerSymbol!!, holding.symbol, earliestLot, from.minusDays(1), today)
            }
        } catch (_: SymbolNotFoundException) {
            // Typically a mistyped or delisted symbol picked at link time: say so instead of a bare stale price.
            metrics.priceRefreshFailure(holding.provider!!.name)
            log.warn("Backfill: {} is not listed by {}", holding.providerSymbol, holding.provider)
            markNotFound(listOf(holding), holding.providerSymbol!!, today)
        } catch (ex: ProviderException) {
            // Linking never fails on provider trouble; the nightly gap-fill will retry.
            metrics.priceRefreshFailure(holding.provider!!.name)
            log.error("Backfill failed for holding {} ({}): {}", holding.symbol, holding.providerSymbol, ex.message)
            if (holding.provider == HoldingProvider.coingecko) {
                fallbackCryptoFill(holding.providerSymbol!!, holding.symbol, earliestLot, today, today)
            }
        }
    }

    /** When an older lot appears, fetch only the missing head range (clamped to the ceiling). */
    fun extendBackfill(holding: Holding, newEarliest: LocalDate, today: LocalDate = LocalDate.now()) {
        val provider = holding.provider ?: return
        val providerSymbol = holding.providerSymbol ?: return
        if (!props.portfolio.backfillOnLink) return
        val currency = priceCurrencyOf(holding)
        val minStored = prices.findMinPriceDate(provider.name, providerSymbol, currency)
            ?: return backfillForHolding(holding, today)
        val from = clampToCeiling(holding, newEarliest, today)
        if (from.isBefore(minStored)) {
            try {
                fetchRange(holding, from, minStored.minusDays(1))
            } catch (_: SymbolNotFoundException) {
                metrics.priceRefreshFailure(provider.name)
                log.warn("Backfill extension: {} is not listed by {}", providerSymbol, provider)
                markNotFound(listOf(holding), providerSymbol, today)
            } catch (ex: ProviderException) {
                metrics.priceRefreshFailure(provider.name)
                log.error("Backfill extension failed for holding {}: {}", holding.symbol, ex.message)
            }
        }
        // Head range beyond the CoinGecko ceiling: Binance covers what CoinGecko can't.
        if (provider == HoldingProvider.coingecko && newEarliest.isBefore(from)) {
            fallbackCryptoFill(providerSymbol, holding.symbol, newEarliest, from.minusDays(1), today)
        }
    }

    /** Binance fallback: daily USDT closes (1 USDT = 1 USD) converted to base with each day's ECB rate and
     *  upserted into the SAME (coingecko, id, base) series the holding reads — the pair symbol is never stored,
     *  so Binance naming can't collide with CoinGecko ids. Days without an FX rate are skipped. Two guards keep
     *  a scheduled run from re-pulling it: short-circuit when the window top is stored, and skip days present. */
    private fun fallbackCryptoFill(
        coinId: String,
        ticker: String,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ) {
        if (from.isAfter(to)) return
        // Top of the window already stored → the whole range was filled on a prior run. Skip the
        // Binance call and the multi-year re-upsert entirely (the common, steady-state path).
        if (prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate(
                CoinGeckoClient.PROVIDER, coinId, baseCurrency, to,
            ) != null
        ) {
            return
        }
        try {
            val pair = ticker.uppercase() + BINANCE_USD_QUOTE
            val usdCloses = cryptoFallback.dailyHistoryUsd(pair, from, to)
            if (usdCloses.isEmpty()) return
            // Days we already hold in this window — so a forced full-range fetch writes only the gap.
            val existing = prices.findAllByProviderAndProviderSymbolAndCurrencyAndPriceDateBetweenOrderByPriceDateAsc(
                CoinGeckoClient.PROVIDER, coinId, baseCurrency, from, to,
            ).mapTo(HashSet()) { it.priceDate }
            if (baseCurrency != USD) refreshFxCurrency(USD, today, earliestNeeded = from)
            val now = Instant.now()
            var stored = 0
            for (day in usdCloses) {
                if (day.date in existing) continue
                val rate =
                    if (baseCurrency == USD) BigDecimal.ONE
                    else fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                        USD, baseCurrency, day.date,
                    )?.rate ?: continue
                val converted = day.price.multiply(rate, java.math.MathContext.DECIMAL64)
                upsertPrice(CoinGeckoClient.PROVIDER, coinId, baseCurrency, DailyPrice(day.date, converted), now)
                stored++
            }
            metrics.priceRefreshed(BinanceClient.PROVIDER)
            if (stored > 0) log.info("Binance fallback stored {} days for {} ({})", stored, coinId, pair)
        } catch (ex: ProviderException) {
            metrics.priceRefreshFailure(BinanceClient.PROVIDER)
            log.error("Binance fallback failed for {}: {}", coinId, ex.message)
        }
    }

    /** Gap-fills a crypto series both ways so a failed request-time backfill self-heals: bootstraps the
     *  ceiling-clamped range, extends the head to the earliest lot, tails to yesterday (today's row comes from
     *  the current-price call). Lots older than CoinGecko's ceiling are covered by the Binance fallback. */
    private fun gapFillCrypto(demand: CoordinateDemand, today: LocalDate) {
        val id = demand.providerSymbol
        val ticker = tickerOf(demand)
        val currency = baseCurrency
        val earliestLot = demand.earliestLot ?: today
        val ceilingFrom = cryptoCeiling(earliestLot, today)
        val yesterday = today.minusDays(1)
        val until = demand.fetchUntil(yesterday)
        val minStored = prices.findMinPriceDate(CoinGeckoClient.PROVIDER, id, currency)
        val maxStored = prices.findMaxPriceDate(CoinGeckoClient.PROVIDER, id, currency)

        if (minStored == null || maxStored == null) {
            if (!ceilingFrom.isAfter(until)) {
                storeAll(CoinGeckoClient.PROVIDER, id, currency, crypto.dailyHistory(id, vsCurrency, ceilingFrom, until))
            }
        } else {
            if (ceilingFrom.isBefore(minStored)) {
                log.info("Crypto head gap-fill for {} [{}..{}]", id, ceilingFrom, minStored.minusDays(1))
                storeAll(CoinGeckoClient.PROVIDER, id, currency, crypto.dailyHistory(id, vsCurrency, ceilingFrom, minStored.minusDays(1)))
                pace(props.portfolio.coingecko.minRequestIntervalMs)
            }
            if (demand.needsTail(maxStored, yesterday)) {
                val rawTailFrom = maxStored.plusDays(1)
                // A tail older than CoinGecko's ceiling (a long-closed position reopened): Binance covers the part before it.
                val tailFrom = cryptoCeiling(rawTailFrom, today)
                if (!tailFrom.isAfter(until)) {
                    storeAll(CoinGeckoClient.PROVIDER, id, currency, crypto.dailyHistory(id, vsCurrency, tailFrom, until))
                }
                if (rawTailFrom.isBefore(tailFrom)) {
                    fallbackCryptoFill(id, ticker, rawTailFrom, minOf(tailFrom.minusDays(1), until), today)
                }
            }
        }
        // Lots older than the CoinGecko ceiling: Binance covers the pre-ceiling head.
        if (earliestLot.isBefore(ceilingFrom)) {
            fallbackCryptoFill(id, ticker, earliestLot, ceilingFrom.minusDays(1), today)
        }
    }

    private fun fetchRange(holding: Holding, from: LocalDate, to: LocalDate) {
        if (from.isAfter(to)) return
        val provider = holding.provider ?: return
        val providerSymbol = holding.providerSymbol ?: return
        val currency = priceCurrencyOf(holding)
        when (provider) {
            HoldingProvider.coingecko -> {
                val history = crypto.dailyHistory(providerSymbol, vsCurrency, from, to)
                storeAll(provider.name, providerSymbol, currency, history)
            }
            HoldingProvider.yahoo, HoldingProvider.eodhd, HoldingProvider.twelvedata -> {
                if (provider != props.portfolio.equityProvider.asHoldingProvider()) {
                    // Linked to an inactive equity provider; relink (or migrate) to resume pricing.
                    log.info("Skipping fetch for {}: linked to inactive provider {}", holding.symbol, provider)
                    return
                }
                val history = equity.dailyHistory(providerSymbol, from, to)
                val effectiveCurrency = reconcileCurrency(listOf(holding), currency, history.currency, from, to)
                storeAll(provider.name, providerSymbol, effectiveCurrency, history.prices)
            }
        }
    }

    private fun cryptoCeiling(candidate: LocalDate, today: LocalDate): LocalDate =
        maxOf(candidate, today.minusDays(props.portfolio.cryptoHistoryCeilingDays))

    private fun clampToCeiling(holding: Holding, candidate: LocalDate, today: LocalDate): LocalDate =
        when (holding.assetClass) {
            HoldingAssetClass.crypto -> maxOf(candidate, today.minusDays(props.portfolio.cryptoHistoryCeilingDays))
            HoldingAssetClass.etf, HoldingAssetClass.stock -> clampToEquityCeiling(candidate, today)
            HoldingAssetClass.fund -> candidate
        }

    /** 0 = uncapped (Yahoo default); positive when a provider limits history (EODHD: 365). */
    private fun clampToEquityCeiling(candidate: LocalDate, today: LocalDate): LocalDate {
        val ceiling = props.portfolio.equityHistoryCeilingDays
        return if (ceiling <= 0) candidate else maxOf(candidate, today.minusDays(ceiling))
    }

    private fun activeEquityConfig() = when (props.portfolio.equityProvider) {
        AppProperties.EquityProviderKind.YAHOO -> props.portfolio.yahoo
        AppProperties.EquityProviderKind.EODHD -> props.portfolio.eodhd
        AppProperties.EquityProviderKind.TWELVE_DATA -> props.portfolio.twelvedata
    }

    private fun priceCurrencyOf(holding: Holding): String =
        if (holding.assetClass == HoldingAssetClass.crypto) baseCurrency else holding.nativeCurrency

    private fun foreignCurrencies(scope: ManualRefreshScope?): List<String> {
        val linked = scope?.let { holdings.findDistinctLinkedForeignNativeCurrenciesByHousehold(baseCurrency, it.householdId) }
            ?: holdings.findDistinctLinkedForeignNativeCurrencies(baseCurrency)
        val traded = scope?.let { lots.findDistinctForeignCurrenciesByHousehold(baseCurrency, it.householdId) }
            ?: lots.findDistinctForeignCurrencies(baseCurrency)
        return (linked + traded).distinct()
    }

    // Built from every household first and only then narrowed, so a coordinate another household still holds
    // open is never treated as closed (nor flagged as not listed) on this household's say alone.
    private fun demandsFor(provider: HoldingProvider, today: LocalDate, scope: ManualRefreshScope?): List<CoordinateDemand> {
        val all = allDemandsFor(provider)
        return if (scope == null) all else all.filter { inScope(provider, it, today, scope) }
    }

    private fun inScope(provider: HoldingProvider, demand: CoordinateDemand, today: LocalDate, scope: ManualRefreshScope): Boolean {
        if (demand.holdings.none { it.householdId == scope.householdId }) return false
        val lastFetched = prices.findLastFetchedAt(provider.name, demand.providerSymbol, demand.currency)
        if (lastFetched != null && !lastFetched.isBefore(scope.skipFetchedSince)) return false
        if (!scope.equitiesStaleOnly || provider == HoldingProvider.coingecko) return true
        // Same cut-off as the valuation's stale flag, so "stale only" refreshes exactly what the notice reports.
        val maxStored = prices.findMaxPriceDate(provider.name, demand.providerSymbol, demand.currency) ?: return true
        return maxStored.isBefore(today.minusDays(props.portfolio.stalePriceThresholdDays))
    }

    // A scheduled run logs a 429 per symbol as before; a manual one stops so the caller can back off.
    private fun abortIfRateLimited(ex: ProviderException, scope: ManualRefreshScope?) {
        if (scope != null && ex is RateLimitedException) throw ex
    }

    /** Linked active holdings on [provider], one demand per price coordinate they share across households. */
    private fun allDemandsFor(provider: HoldingProvider): List<CoordinateDemand> {
        val linked = holdings.findAllLinkedActive().filter { it.provider == provider }
        if (linked.isEmpty()) return emptyList()
        val lotsByHolding = lots.findAllByHoldingIdIn(linked.map { it.id }).groupBy { it.holdingId }
        return linked
            .groupBy { it.providerSymbol!! to priceCurrencyOf(it) }
            .map { (coordinate, group) -> demandOf(coordinate.first, coordinate.second, group, lotsByHolding) }
    }

    private fun demandOf(
        providerSymbol: String,
        currency: String,
        group: List<Holding>,
        lotsByHolding: Map<UUID, List<HoldingLot>>,
    ): CoordinateDemand {
        val groupLots = group.map { lotsByHolding[it.id].orEmpty() }
        val allLots = groupLots.flatten()
        // Exact zero, as the valuation uses: a dust remnant is still a position and keeps its daily price.
        val anyOpen = groupLots.any { it.isEmpty() || netQuantity(it).signum() != 0 }
        return CoordinateDemand(
            providerSymbol = providerSymbol,
            currency = currency,
            holdings = group,
            earliestLot = allLots.minOfOrNull { it.tradedOn },
            closedOn = if (anyOpen) null else allLots.maxOf { it.tradedOn },
        )
    }

    private fun netQuantity(holdingLots: List<HoldingLot>): BigDecimal =
        PortfolioValuationCalculator.replay(holdingLots.map { it.toEntry() }, props.portfolio.costMethod).netQuantity

    private fun tickerOf(demand: CoordinateDemand): String = demand.holdings.first().symbol

    // Re-checked on every 7th day since the first 404, so a relisting or a transient 404 heals within a week.
    private fun inNotFoundBackoff(demand: CoordinateDemand, today: LocalDate): Boolean {
        val since = demand.holdings.map { it.providerNotFoundSince ?: return false }.min()
        val days = ChronoUnit.DAYS.between(since, today)
        return days <= 0 || days % NOT_FOUND_RECHECK_DAYS != 0L
    }

    private fun markNotFound(group: List<Holding>, providerSymbol: String, today: LocalDate) {
        runInTx { holdings.markProviderNotFound(group.map { it.id }, providerSymbol, today) }
    }

    private fun clearNotFound(group: List<Holding>, providerSymbol: String) {
        if (group.none { it.providerNotFoundSince != null }) return
        runInTx { holdings.clearProviderNotFound(group.map { it.id }, providerSymbol) }
        log.info("Equity refresh: {} is listed again", providerSymbol)
    }

    private fun earliestLotDateFor(currency: String): LocalDate? =
        holdings.findAllLinkedActive()
            .flatMap { lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(it.id) }
            .filter { it.currency == currency }
            .minOfOrNull { it.tradedOn }

    private fun storeAll(provider: String, symbol: String, currency: String, history: List<DailyPrice>) {
        val now = Instant.now()
        history.forEach { upsertPrice(provider, symbol, currency, it, now) }
    }

    private fun upsertPrice(provider: String, symbol: String, currency: String, day: DailyPrice, asOf: Instant) {
        runInTx {
            val existing = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate(
                provider, symbol, currency, day.date,
            )
            if (existing != null) {
                existing.price = day.price
                existing.asOf = asOf
                existing.fetchedAt = Instant.now()
                prices.save(existing)
            } else {
                try {
                    prices.save(
                        PricePoint(
                            provider = provider,
                            providerSymbol = symbol,
                            currency = currency,
                            price = day.price,
                            priceDate = day.date,
                            asOf = asOf,
                        )
                    )
                } catch (_: DataIntegrityViolationException) {
                    // Race with a concurrent backfill: the row exists now, nothing to do.
                }
            }
        }
    }

    private fun upsertFx(currency: String, day: DailyPrice) {
        runInTx {
            val existing = fxRates.findByProviderAndBaseCurrencyAndQuoteCurrencyAndRateDate(
                FrankfurterClient.PROVIDER, currency, baseCurrency, day.date,
            )
            if (existing != null) {
                existing.rate = day.price
                existing.fetchedAt = Instant.now()
                fxRates.save(existing)
            } else {
                try {
                    fxRates.save(
                        FxRate(
                            provider = FrankfurterClient.PROVIDER,
                            baseCurrency = currency,
                            quoteCurrency = baseCurrency,
                            rate = day.price,
                            rateDate = day.date,
                        )
                    )
                } catch (_: DataIntegrityViolationException) {
                    // Concurrent insert; the observation is already stored.
                }
            }
        }
    }

    private fun runInTx(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }

    private fun pace(minIntervalMs: Long) {
        if (minIntervalMs > 0) {
            try {
                Thread.sleep(minIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        // First FX fetch when no lots reference the currency yet.
        const val DEFAULT_FX_LOOKBACK_DAYS: Long = 30

        // Head-fetch margin so weekend/holiday dates can forward-fill from a business day.
        const val FX_HEAD_MARGIN_DAYS: Long = 7
        const val EQUITY_HEAD_MARGIN_DAYS: Long = 7

        const val USD = "USD"

        const val NOT_FOUND_RECHECK_DAYS: Long = 7

        // Binance quotes crypto in USDT; assumed 1:1 with USD before FX conversion.
        const val BINANCE_USD_QUOTE = "USDT"
    }
}
