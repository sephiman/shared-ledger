package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager

/** One-off idempotent startup migration: re-resolves equity holdings still on a previous provider's
 *  coordinates to the active one, via ISIN then ticker. A unique match relinks and re-backfills, an
 *  ambiguous one unlinks for manual re-search, a provider outage retries next startup. */
@Component
class EquityProviderMigration(
    private val holdings: HoldingRepository,
    private val equity: EquityPriceProvider,
    private val refresh: PriceRefreshService,
    private val props: AppProperties,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(EquityProviderMigration::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun migrate() {
        val active = props.portfolio.equityProvider.asHoldingProvider()
        val toMigrate = holdings.findAllLinkedActive()
            .filter { it.assetClass == HoldingAssetClass.etf || it.assetClass == HoldingAssetClass.stock }
            .filter { it.provider != active }
        if (toMigrate.isEmpty()) return
        log.info("Migrating {} equity holdings to provider {}", toMigrate.size, active)

        for (holding in toMigrate) {
            val candidates = try {
                resolve(holding.isin, holding.symbol)
            } catch (ex: ProviderException) {
                log.warn("Provider unavailable while migrating {}; will retry next startup: {}", holding.symbol, ex.message)
                continue
            }
            val match = pickUnique(candidates, holding.symbol)
            TransactionTemplate(txManager).execute {
                val managed = holdings.findById(holding.id).orElse(null) ?: return@execute
                if (match == null) {
                    log.warn("No unique {} match for {} ({}): unlinking for manual re-search", active, holding.symbol, holding.isin)
                    managed.provider = null
                    managed.providerSymbol = null
                } else {
                    managed.provider = active
                    managed.providerSymbol = match.providerSymbol
                    match.currency?.let { managed.nativeCurrency = it }
                    log.info("Relinked {} to {} {}", holding.symbol, active, match.providerSymbol)
                }
                holdings.save(managed)
            }
            if (match != null) {
                val relinked = holdings.findById(holding.id).orElse(null)
                if (relinked != null) refresh.backfillForHolding(relinked)
            }
            pace()
        }
    }

    private fun resolve(isin: String?, symbol: String): List<SymbolCandidate> {
        val byIsin = isin?.let { equity.searchByIsin(it) }.orEmpty()
        if (byIsin.isNotEmpty()) return byIsin
        return equity.search(symbol)
    }

    /** A single candidate wins; among several, a unique base-ticker match on the holding's symbol. */
    private fun pickUnique(candidates: List<SymbolCandidate>, symbol: String): SymbolCandidate? {
        candidates.singleOrNull()?.let { return it }
        return candidates.filter { (it.symbol ?: it.providerSymbol.substringBefore('.')).equals(symbol, ignoreCase = true) }
            .singleOrNull()
    }

    private fun pace() {
        val interval = when (props.portfolio.equityProvider) {
            AppProperties.EquityProviderKind.YAHOO -> props.portfolio.yahoo.minRequestIntervalMs
            AppProperties.EquityProviderKind.EODHD -> props.portfolio.eodhd.minRequestIntervalMs
            AppProperties.EquityProviderKind.TWELVE_DATA -> props.portfolio.twelvedata.minRequestIntervalMs
        }
        if (interval > 0) {
            try {
                Thread.sleep(interval)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
