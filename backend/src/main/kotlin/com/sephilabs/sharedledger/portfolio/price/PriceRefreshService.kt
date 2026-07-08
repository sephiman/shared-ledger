package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingLotRepository
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Gap-fill and backfill engine for price_history and fx_rates. Idempotent: rows are
 * upserted by their unique (provider coordinates, date) key, so overlapping runs and
 * re-runs after failures self-heal. Every refresh resumes from the last stored date.
 */
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
    fun refreshCrypto(today: LocalDate) {
        val linked = linkedHoldings(HoldingProvider.coingecko)
        // Ticker per coordinate (BTC for coingecko id "bitcoin") to build Binance fallback pairs.
        val tickerByCoord = linked.associate { it.providerSymbol!! to it.symbol }
        val holdingsByCoord = linked.groupBy { it.providerSymbol!! }
        val coords = tickerByCoord.keys.toList()
        if (coords.isEmpty()) return

        for (id in coords) {
            val ticker = tickerByCoord.getValue(id)
            val group = holdingsByCoord.getValue(id)
            try {
                gapFillCrypto(id, ticker, group, today)
            } catch (ex: ProviderException) {
                metrics.priceRefreshFailure(CoinGeckoClient.PROVIDER)
                log.error("Crypto gap-fill failed for {}: {}; trying Binance fallback", id, ex.message)
                // Binance covers from the last stored day, or the earliest lot when CoinGecko
                // never populated the series at all.
                val maxStored = prices.findMaxPriceDate(CoinGeckoClient.PROVIDER, id, baseCurrency)
                val from = maxStored?.plusDays(1) ?: cryptoCeiling(earliestLotOf(group, today), today)
                fallbackCryptoFill(id, ticker, from, today.minusDays(1), today)
            }
            pace(props.portfolio.coingecko.minRequestIntervalMs)
        }
        try {
            val current = crypto.currentPrices(coords, vsCurrency)
            val now = Instant.now()
            current.forEach { (id, price) ->
                upsertPrice(CoinGeckoClient.PROVIDER, id, baseCurrency, DailyPrice(today, price), now)
            }
            metrics.priceRefreshed(CoinGeckoClient.PROVIDER)
        } catch (ex: ProviderException) {
            metrics.priceRefreshFailure(CoinGeckoClient.PROVIDER)
            log.error("Crypto current-price refresh failed: {}", ex.message)
        }
    }

    /**
     * Nightly, after FX: per distinct linked equity symbol, gap-fill EOD prices to today.
     * Only holdings linked to the ACTIVE equity provider are refreshed; holdings still
     * linked to another one keep their stored prices and show stale/no-price until
     * relinked (or migrated). A failing symbol never crashes the job.
     */
    fun refreshEquities(today: LocalDate) {
        val active = props.portfolio.equityProvider.asHoldingProvider()
        val symbols = linkedHoldings(active)
        val byCoordinate = symbols.groupBy { it.providerSymbol!! to priceCurrencyOf(it) }
        for ((coordinate, group) in byCoordinate) {
            val (symbol, currency) = coordinate
            try {
                if (refreshEquitySymbol(active.name, symbol, currency, group, today)) {
                    metrics.priceRefreshed(active.name)
                }
            } catch (ex: ProviderException) {
                metrics.priceRefreshFailure(active.name)
                log.error("Equity refresh failed for {}: {}", symbol, ex.message)
            }
            pace(activeEquityConfig().minRequestIntervalMs)
        }
    }

    /**
     * Fills an equity series in both directions so a failed request-time backfill self-heals:
     * bootstraps the full ceiling-clamped range when nothing is stored, extends the head down
     * to the earliest lot, and tails up to today. Returns true if anything was fetched.
     */
    private fun refreshEquitySymbol(
        provider: String,
        symbol: String,
        currency: String,
        group: List<Holding>,
        today: LocalDate,
    ): Boolean {
        val minStored = prices.findMinPriceDate(provider, symbol, currency)
        val maxStored = prices.findMaxPriceDate(provider, symbol, currency)
        val desiredFrom = clampToEquityCeiling(earliestLotOf(group, today), today)
        if (minStored == null || maxStored == null) {
            return fetchEquityRange(provider, symbol, group, currency, desiredFrom, today, today, isHead = true)
        }
        var fetched = false
        if (desiredFrom.isBefore(minStored)) {
            log.info("Equity head gap-fill for {} [{}..{}]", symbol, desiredFrom, minStored.minusDays(1))
            fetched = fetchEquityRange(provider, symbol, group, currency, desiredFrom, minStored.minusDays(1), today, isHead = true)
            pace(activeEquityConfig().minRequestIntervalMs)
        }
        if (maxStored.isBefore(today)) {
            fetched = fetchEquityRange(provider, symbol, group, currency, maxStored.plusDays(1), today, today, isHead = false) || fetched
        }
        return fetched
    }

    /**
     * A head fetch also tops up the foreign-currency FX head so old-date valuations convert
     * (the nightly FX job only tails). Returns false when the range is empty.
     */
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

    /**
     * The provider's reported listing currency (Yahoo chart meta) is the source of truth:
     * search results carry none, so a holding's native_currency can be wrong until the
     * first fetch. On mismatch the holdings are corrected and prices stored under the
     * real currency — otherwise a USD listing would be valued as EUR with FX=1.
     */
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
    fun refreshFx(today: LocalDate) {
        val currencies = (
            holdings.findDistinctLinkedForeignNativeCurrencies(baseCurrency) +
                lots.findDistinctForeignCurrencies(baseCurrency)
            ).distinct()
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

    /**
     * Fetches FX history for one currency: tail gap-fill up to [today], plus a head
     * extension when [earliestNeeded] (e.g. an old lot date) precedes the stored range.
     * The head fetch starts a few days early so weekend dates forward-fill from the
     * preceding business day.
     */
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

    /**
     * Ranged backfill for a freshly linked holding:
     * from = max(earliest lot date, provider ceiling), to = today.
     */
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
        if (!holding.linked || !props.portfolio.backfillOnLink) return
        val currency = priceCurrencyOf(holding)
        val minStored = prices.findMinPriceDate(holding.provider!!.name, holding.providerSymbol!!, currency)
            ?: return backfillForHolding(holding, today)
        val from = clampToCeiling(holding, newEarliest, today)
        if (from.isBefore(minStored)) {
            try {
                fetchRange(holding, from, minStored.minusDays(1))
            } catch (ex: ProviderException) {
                metrics.priceRefreshFailure(holding.provider!!.name)
                log.error("Backfill extension failed for holding {}: {}", holding.symbol, ex.message)
            }
        }
        // Head range beyond the CoinGecko ceiling: Binance covers what CoinGecko can't.
        if (holding.provider == HoldingProvider.coingecko && newEarliest.isBefore(from)) {
            fallbackCryptoFill(holding.providerSymbol!!, holding.symbol, newEarliest, from.minusDays(1), today)
        }
    }

    /**
     * Binance fallback: daily USDT closes for {ticker}USDT (1 USDT = 1 USD by assumption),
     * converted into the base currency with the ECB rate of each day and upserted into
     * the SAME (coingecko, id, base) series the holding reads — the pair symbol is never
     * persisted, so Binance naming can't collide with CoinGecko ids. Days without an FX
     * rate are skipped; unknown pairs are a silent no-op.
     *
     * Idempotent and cheap on repeat: this range sits below the CoinGecko ceiling and is fixed
     * once filled, so a scheduled refresh must not re-pull it every run. Two guards ensure that —
     * a short-circuit when the top of the window is already stored (it was covered by CoinGecko
     * the day before it crossed the ceiling), and a skip of any day already present when a fetch
     * does happen (initial fill, or a recent gap after a CoinGecko outage).
     */
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

    /**
     * Gap-fills a crypto series in both directions so a failed request-time backfill self-heals:
     * bootstraps the whole ceiling-clamped range when nothing is stored, extends the head down to
     * the earliest lot, and tails up to yesterday (today's row comes from the current-price call).
     * A lot older than CoinGecko's history ceiling is covered by the Binance fallback.
     */
    private fun gapFillCrypto(id: String, ticker: String, group: List<Holding>, today: LocalDate) {
        val currency = baseCurrency
        val earliestLot = earliestLotOf(group, today)
        val ceilingFrom = cryptoCeiling(earliestLot, today)
        val yesterday = today.minusDays(1)
        val minStored = prices.findMinPriceDate(CoinGeckoClient.PROVIDER, id, currency)
        val maxStored = prices.findMaxPriceDate(CoinGeckoClient.PROVIDER, id, currency)

        if (minStored == null || maxStored == null) {
            if (!ceilingFrom.isAfter(yesterday)) {
                storeAll(CoinGeckoClient.PROVIDER, id, currency, crypto.dailyHistory(id, vsCurrency, ceilingFrom, yesterday))
            }
        } else {
            if (ceilingFrom.isBefore(minStored)) {
                log.info("Crypto head gap-fill for {} [{}..{}]", id, ceilingFrom, minStored.minusDays(1))
                storeAll(CoinGeckoClient.PROVIDER, id, currency, crypto.dailyHistory(id, vsCurrency, ceilingFrom, minStored.minusDays(1)))
                pace(props.portfolio.coingecko.minRequestIntervalMs)
            }
            val tailFrom = maxStored.plusDays(1)
            if (!tailFrom.isAfter(yesterday)) {
                storeAll(CoinGeckoClient.PROVIDER, id, currency, crypto.dailyHistory(id, vsCurrency, tailFrom, yesterday))
            }
        }
        // Lots older than the CoinGecko ceiling: Binance covers the pre-ceiling head.
        if (earliestLot.isBefore(ceilingFrom)) {
            fallbackCryptoFill(id, ticker, earliestLot, ceilingFrom.minusDays(1), today)
        }
    }

    private fun fetchRange(holding: Holding, from: LocalDate, to: LocalDate) {
        if (from.isAfter(to)) return
        val currency = priceCurrencyOf(holding)
        val provider = holding.provider!!
        when (provider) {
            HoldingProvider.coingecko -> {
                val history = crypto.dailyHistory(holding.providerSymbol!!, vsCurrency, from, to)
                storeAll(provider.name, holding.providerSymbol!!, currency, history)
            }
            HoldingProvider.yahoo, HoldingProvider.eodhd, HoldingProvider.twelvedata -> {
                if (provider != props.portfolio.equityProvider.asHoldingProvider()) {
                    // Linked to an inactive equity provider; relink (or migrate) to resume pricing.
                    log.info("Skipping fetch for {}: linked to inactive provider {}", holding.symbol, provider)
                    return
                }
                val history = equity.dailyHistory(holding.providerSymbol!!, from, to)
                val effectiveCurrency = reconcileCurrency(listOf(holding), currency, history.currency, from, to)
                storeAll(provider.name, holding.providerSymbol!!, effectiveCurrency, history.prices)
            }
        }
    }

    private fun earliestLotOf(group: List<Holding>, default: LocalDate): LocalDate =
        group.mapNotNull { lots.findMinTradedOn(it.id) }.minOrNull() ?: default

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

    private fun linkedHoldings(provider: HoldingProvider): List<Holding> =
        holdings.findAllLinkedActive().filter { it.provider == provider }

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
                } catch (ignored: DataIntegrityViolationException) {
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
                } catch (ignored: DataIntegrityViolationException) {
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
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        // First FX fetch when no lots reference the currency yet.
        const val DEFAULT_FX_LOOKBACK_DAYS: Long = 30

        // Head-fetch margin so weekend/holiday dates can forward-fill from a business day.
        const val FX_HEAD_MARGIN_DAYS: Long = 7

        const val USD = "USD"

        // Binance quotes crypto in USDT; assumed 1:1 with USD before FX conversion.
        const val BINANCE_USD_QUOTE = "USDT"
    }
}
