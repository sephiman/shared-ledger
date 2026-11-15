package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.notification.TelegramClient
import com.sephilabs.sharedledger.notification.TelegramCrypto
import com.sephilabs.sharedledger.notification.TelegramMessageFormatter
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingLotRepository
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import com.sephilabs.sharedledger.portfolio.PortfolioValuationCalculator
import com.sephilabs.sharedledger.portfolio.price.FxRateRepository
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import com.sephilabs.sharedledger.portfolio.toEntry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class PriceCoordinate(
    val provider: String,
    val providerSymbol: String,
    val currency: String,
)

/** Evaluates active alerts after price refreshes using single conditional updates so overlapping runs never
 *  fire twice, then dispatches pending deliveries with bounded spaced retries. */
@Service
class PriceAlertEvaluator(
    private val alerts: PriceAlertRepository,
    private val holdings: HoldingRepository,
    private val lots: HoldingLotRepository,
    private val prices: PricePointRepository,
    private val fxRates: FxRateRepository,
    private val households: HouseholdRepository,
    private val telegramSettings: TelegramSettingsRepository,
    private val crypto: TelegramCrypto,
    private val telegramClient: TelegramClient,
    private val formatter: TelegramMessageFormatter,
    private val props: AppProperties,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val baseCurrency: String get() = props.portfolio.baseCurrency

    /** Evaluates active alerts for [refreshedCoordinates] against their current (non-historical, non-stale)
     *  price point. Households without an active deliverable Telegram destination are skipped untouched. */
    fun evaluateAndDeliver(
        refreshedCoordinates: Set<PriceCoordinate>,
        today: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
    ) {
        if (refreshedCoordinates.isNotEmpty()) {
            evaluateCoordinates(refreshedCoordinates, today, now)
        }
        deliverPendingAlerts(today, now)
    }

    fun evaluateCoordinates(
        refreshedCoordinates: Set<PriceCoordinate>,
        today: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
    ) {
        if (refreshedCoordinates.isEmpty()) return

        val activeAlerts = TransactionTemplate(txManager).execute {
            alerts.findAllByStatus(AlertStatus.ACTIVE)
        }
        if (activeAlerts.isEmpty()) return

        val alertsByHousehold = activeAlerts.groupBy { it.householdId }
        for ((householdId, householdAlerts) in alertsByHousehold) {
            if (!hasActiveTelegram(householdId)) continue

            // Always apply expiry silently before any threshold comparison.
            TransactionTemplate(txManager).executeWithoutResult {
                alerts.expireDueForHousehold(householdId, now)
            }

            val holdingIds = householdAlerts.map { it.holdingId }.distinct()
            val holdingMap = holdings.findAllById(holdingIds).associateBy { it.id }

            for (alert in householdAlerts) {
                if (alert.expiresAt != null && !alert.expiresAt!!.isAfter(now)) continue
                val holding = holdingMap[alert.holdingId] ?: continue
                if (!holding.active || !holding.linked || holding.assetClass == HoldingAssetClass.fund) continue

                val coord = coordinateOf(holding) ?: continue
                if (coord !in refreshedCoordinates) continue

                val currentPoint = currentPricePointForEvaluation(holding, coord, today) ?: continue
                if (!PriceAlertFreshness.isFreshForEvaluation(holding, currentPoint, today, now, props)) continue

                val netQty = openQuantity(holding.id)
                if (netQty.signum() <= 0) continue

                if (!matchesCondition(alert.direction, currentPoint.price, alert.threshold)) continue

                val won = TransactionTemplate(txManager).execute {
                    alerts.fireIfActive(alert.id, now, currentPoint.price, currentPoint.asOf)
                }
                if (won == 1) {
                    log.info(
                        "price_alert_fired household={} alert={} holding={} price={} threshold={}",
                        householdId, alert.id, holding.id, currentPoint.price, alert.threshold,
                    )
                }
            }
        }
    }

    /** Retries all FIRED-but-undelivered alerts across every household, obeying spacing and attempt bounds. */
    fun deliverPendingAlerts(today: LocalDate = LocalDate.now(), now: Instant = Instant.now()) {
        val retryBefore = now.minus(ALERT_DELIVERY_RETRY_MIN_SPACING)
        val candidates = TransactionTemplate(txManager).execute {
            alerts.findPendingDeliveries(MAX_ALERT_DELIVERY_ATTEMPTS, retryBefore)
        }
        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            deliverOne(candidate.id, candidate.householdId, candidate.holdingId, today, now)
        }
    }

    private fun deliverOne(
        alertId: UUID,
        householdId: UUID,
        holdingId: UUID,
        today: LocalDate,
        now: Instant,
    ) {
        val settings = telegramSettings.findByHouseholdId(householdId)
        if (settings == null || !settings.active || !settings.isDeliverable()) return

        val retryBefore = now.minus(ALERT_DELIVERY_RETRY_MIN_SPACING)
        val claimed = TransactionTemplate(txManager).execute {
            alerts.claimDeliveryAttempt(alertId, MAX_ALERT_DELIVERY_ATTEMPTS, retryBefore, now)
        }
        if (claimed != 1) return

        val error = try {
            val alert = alerts.findById(alertId).orElse(null)
                ?: return
            val holding = holdings.findById(holdingId).orElse(null)
            val household = households.findById(householdId).orElse(null)
            if (holding == null || household == null) {
                recordError(alertId, "holding_or_household_missing", now)
                return
            }

            val token = crypto.decrypt(settings.botTokenEnc!!)
            val locale = Locale.forLanguageTag(household.defaultLocale.ifBlank { "en" })
            val message = buildMessage(alert, holding, locale, today)
            val result = telegramClient.sendMessage(token, settings.chatId!!, message)
            if (result.ok) {
                TransactionTemplate(txManager).executeWithoutResult {
                    alerts.markDelivered(alertId, Instant.now())
                }
                log.info("price_alert_delivered household={} alert={}", householdId, alertId)
                null
            } else {
                result.description?.takeIf { it.isNotBlank() } ?: "Telegram delivery failed"
            }
        } catch (ex: Exception) {
            ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName
        }

        if (error != null) {
            recordError(alertId, error, Instant.now())
            log.warn("price_alert_delivery_failed household={} alert={} error={}", householdId, alertId, error)
        }
    }

    private fun recordError(alertId: UUID, error: String, now: Instant) {
        TransactionTemplate(txManager).executeWithoutResult {
            alerts.recordDeliveryError(alertId, error.take(500), now)
        }
    }

    private fun buildMessage(alert: PriceAlert, holding: Holding, locale: Locale, today: LocalDate): String {
        val firedPrice = alert.firedPrice ?: alert.threshold
        val entries = lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holding.id).map { it.toEntry() }
        val state = PortfolioValuationCalculator.replay(entries, props.portfolio.costMethod)
        val fxToBase = if (alert.currency == baseCurrency) {
            BigDecimal.ONE
        } else {
            fxRates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                alert.currency, baseCurrency, today,
            )?.rate
        }
        val valuation = fxToBase?.let { fx ->
            PortfolioValuationCalculator.value(
                state = state,
                price = PortfolioValuationCalculator.PriceInput(
                    price = firedPrice,
                    priceDate = today,
                    currency = alert.currency,
                    fxToBase = fx,
                    observedAt = alert.firedPriceAsOf ?: alert.firedAt ?: Instant.now(),
                ),
                asOfDate = today,
                staleThresholdDays = props.portfolio.stalePriceThresholdDays,
            )
        }
        return formatter.formatPriceAlert(
            symbol = holding.symbol,
            label = holding.label,
            direction = alert.direction,
            threshold = alert.threshold,
            priceCurrency = alert.currency,
            firedPrice = firedPrice,
            priceObservedAt = alert.firedPriceAsOf ?: alert.firedAt ?: Instant.now(),
            quantity = state.netQuantity,
            currentValueBase = valuation?.currentValueBase,
            unrealizedPnlBase = valuation?.unrealizedPnlAbs,
            unrealizedPnlPct = valuation?.unrealizedPnlPct,
            baseCurrency = baseCurrency,
            note = alert.note,
            locale = locale,
        )
    }

    private fun hasActiveTelegram(householdId: UUID): Boolean =
        telegramSettings.findByHouseholdId(householdId)?.let { it.active && it.isDeliverable() } == true

    private fun coordinateOf(holding: Holding): PriceCoordinate? {
        val provider = holding.provider ?: return null
        val symbol = holding.providerSymbol ?: return null
        val currency = if (holding.assetClass == HoldingAssetClass.crypto) baseCurrency else holding.nativeCurrency
        return PriceCoordinate(provider.name, symbol, currency)
    }

    /** Looks up ONLY the current price point: today's row for crypto, latest row <= today for equities. */
    private fun currentPricePointForEvaluation(holding: Holding, coord: PriceCoordinate, today: LocalDate): PricePoint? =
        when (holding.assetClass) {
            HoldingAssetClass.crypto -> prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate(
                coord.provider, coord.providerSymbol, coord.currency, today,
            )
            HoldingAssetClass.etf, HoldingAssetClass.stock ->
                prices.findFirstByProviderAndProviderSymbolAndCurrencyAndPriceDateLessThanEqualOrderByPriceDateDesc(
                    coord.provider, coord.providerSymbol, coord.currency, today,
                )
            HoldingAssetClass.fund -> null
        }

    private fun openQuantity(holdingId: UUID): BigDecimal {
        val entries = lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holdingId).map { it.toEntry() }
        return PortfolioValuationCalculator.replay(entries, props.portfolio.costMethod).netQuantity
    }

    private fun matchesCondition(direction: AlertDirection, price: BigDecimal, threshold: BigDecimal): Boolean =
        when (direction) {
            AlertDirection.ABOVE -> price.compareTo(threshold) >= 0
            AlertDirection.BELOW -> price.compareTo(threshold) <= 0
        }
}
