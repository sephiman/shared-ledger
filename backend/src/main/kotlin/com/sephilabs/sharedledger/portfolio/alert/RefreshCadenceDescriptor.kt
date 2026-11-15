package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.price.asHoldingProvider
import org.springframework.scheduling.support.CronExpression
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class RefreshCadenceUnit { MINUTES, HOURS, DAYS }

data class RefreshCadenceDto(
    val unit: RefreshCadenceUnit,
    val interval: Int,
    val weekdaysOnly: Boolean,
    val dailyTime: String? = null,
    val includesNightlyClose: Boolean = false,
)

/** Derives human-accurate refresh cadence from the active scheduler crons and provider capabilities. */
object RefreshCadenceDescriptor {

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH)

    fun forHolding(holding: Holding, props: AppProperties): RefreshCadenceDto? {
        val provider = holding.provider ?: return null
        if (holding.assetClass == HoldingAssetClass.fund) return null
        val schedulerZone = ZoneId.of(props.scheduler.timezone)
        val displayZone = props.displayZoneId()
        return when (holding.assetClass) {
            HoldingAssetClass.crypto -> {
                if (provider != HoldingProvider.coingecko) return null
                fromCron(props.portfolio.cryptoRefreshCron, schedulerZone, displayZone, includesNightlyClose = false)
            }
            HoldingAssetClass.etf, HoldingAssetClass.stock -> {
                if (provider != props.portfolio.equityProvider.asHoldingProvider()) return null
                if (props.portfolio.equityProvider.allowsIntradayRefresh) {
                    fromCron(props.portfolio.equityIntradayRefreshCron, schedulerZone, displayZone, includesNightlyClose = true)
                } else {
                    fromCron(props.portfolio.equityRefreshCron, schedulerZone, displayZone, includesNightlyClose = false)
                }
            }
            HoldingAssetClass.fund -> null
        }
    }

    fun fromCron(
        cron: String,
        schedulerZone: ZoneId,
        displayZone: ZoneId,
        includesNightlyClose: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(schedulerZone),
    ): RefreshCadenceDto {
        val expr = runCatching { CronExpression.parse(cron) }.getOrNull()
            ?: return RefreshCadenceDto(RefreshCadenceUnit.DAYS, 1, weekdaysOnly = false, dailyTime = null, includesNightlyClose)

        // Anchor on a Monday at 00:00 in schedulerZone so weekday-only crons still yield two consecutive runs.
        val monday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val anchor = ZonedDateTime.of(monday, LocalTime.MIDNIGHT, schedulerZone).minusSeconds(1)
        val t1 = expr.next(anchor)
        if (t1 == null || Duration.between(anchor, t1).toDays() > 7) {
            return RefreshCadenceDto(RefreshCadenceUnit.DAYS, 1, weekdaysOnly = false, dailyTime = null, includesNightlyClose)
        }
        val t2 = expr.next(t1)
            ?: return RefreshCadenceDto(RefreshCadenceUnit.DAYS, 1, weekdaysOnly = false, dailyTime = null, includesNightlyClose)

        // Probe from Saturday midnight to detect whether weekends are excluded.
        val saturday = monday.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        val satAnchor = ZonedDateTime.of(saturday, LocalTime.MIDNIGHT, schedulerZone).minusSeconds(1)
        val nextFromSat = expr.next(satAnchor)
        val weekdaysOnly = nextFromSat == null ||
            (nextFromSat.toLocalDate().dayOfWeek != DayOfWeek.SATURDAY && nextFromSat.toLocalDate().dayOfWeek != DayOfWeek.SUNDAY)

        val stepMinutes = Duration.between(t1, t2).toMinutes().coerceAtLeast(1)
        return when {
            stepMinutes < 60 -> RefreshCadenceDto(
                unit = RefreshCadenceUnit.MINUTES,
                interval = stepMinutes.toInt(),
                weekdaysOnly = weekdaysOnly,
                dailyTime = null,
                includesNightlyClose = includesNightlyClose,
            )
            stepMinutes < 24 * 60 -> RefreshCadenceDto(
                unit = RefreshCadenceUnit.HOURS,
                interval = maxOf(1, (stepMinutes / 60).toInt()),
                weekdaysOnly = weekdaysOnly,
                dailyTime = null,
                includesNightlyClose = includesNightlyClose,
            )
            else -> {
                val days = maxOf(1, (stepMinutes / (24 * 60)).toInt())
                val nextRun = expr.next(now) ?: t1
                val dailyTime = TIME_FORMAT.format(nextRun.withZoneSameInstant(displayZone))
                RefreshCadenceDto(
                    unit = RefreshCadenceUnit.DAYS,
                    interval = days,
                    weekdaysOnly = weekdaysOnly,
                    dailyTime = dailyTime,
                    includesNightlyClose = includesNightlyClose,
                )
            }
        }
    }
}
