package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Shared freshness rules for price-alert evaluation, matching the UI thresholds cleanly. */
object PriceAlertFreshness {

    val CRYPTO_STALE_AFTER: Duration = Duration.ofHours(3)
    const val EQUITY_STALE_AFTER_DAYS: Long = 4

    fun isFreshForEvaluation(
        holding: Holding,
        point: PricePoint,
        today: LocalDate,
        now: Instant,
        props: AppProperties,
    ): Boolean {
        if (holding.providerNotFoundSince != null) return false
        if (ChronoUnit.DAYS.between(point.priceDate, today) > props.portfolio.stalePriceThresholdDays) return false
        return when (holding.assetClass) {
            HoldingAssetClass.crypto -> {
                point.priceDate == today && !Duration.between(point.asOf, now).minus(CRYPTO_STALE_AFTER).isPositive
            }
            HoldingAssetClass.etf, HoldingAssetClass.stock -> {
                !point.priceDate.isAfter(today) && ChronoUnit.DAYS.between(point.priceDate, today) <= EQUITY_STALE_AFTER_DAYS
            }
            HoldingAssetClass.fund -> false
        }
    }
}
