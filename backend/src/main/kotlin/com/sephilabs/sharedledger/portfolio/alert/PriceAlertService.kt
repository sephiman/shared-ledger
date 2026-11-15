package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingLotRepository
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import com.sephilabs.sharedledger.portfolio.PortfolioValuationCalculator
import com.sephilabs.sharedledger.portfolio.PortfolioValuationService
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import com.sephilabs.sharedledger.portfolio.toEntry
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class PriceAlertService(
    private val alerts: PriceAlertRepository,
    private val holdings: HoldingRepository,
    private val lots: HoldingLotRepository,
    private val prices: PricePointRepository,
    private val households: HouseholdRepository,
    private val users: UserRepository,
    private val telegramSettings: TelegramSettingsRepository,
    private val valuation: PortfolioValuationService,
    private val props: AppProperties,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isTelegramActive(householdId: UUID): Boolean =
        telegramSettings.findByHouseholdId(householdId)?.let { it.active && it.isDeliverable() } == true

    fun requireTelegramActive(householdId: UUID) {
        if (!isTelegramActive(householdId)) throw AppException.notFound()
    }

    @Transactional
    fun list(householdId: UUID, holdingId: UUID? = null): List<PriceAlertDto> {
        requireTelegramActive(householdId)
        val now = Instant.now()
        alerts.expireDueForHousehold(householdId, now)

        val rows = if (holdingId != null) {
            alerts.findAllByHouseholdIdAndHoldingIdOrderByCreatedAtDesc(householdId, holdingId)
        } else {
            alerts.findAllByHouseholdIdOrderByCreatedAtDesc(householdId)
        }
        if (rows.isEmpty()) return emptyList()

        val holdingMap = holdings.findAllByHouseholdIdOrderBySymbolAsc(householdId).associateBy { it.id }
        val userMap = users.findAllById(rows.map { it.createdByUserId }.distinct()).associateBy { it.id }
        val today = LocalDate.now()

        return rows.mapNotNull { alert ->
            val holding = holdingMap[alert.holdingId] ?: return@mapNotNull null
            val currentPrice = currentPriceInCurrency(holding, today)
            toDto(alert, holding, currentPrice, userMap[alert.createdByUserId]?.email)
        }.sortedWith(
            compareBy<PriceAlertDto> { if (it.status == AlertStatus.ACTIVE) 0 else 1 }
                .thenByDescending { it.createdAt }
        )
    }

    @Transactional
    fun create(householdId: UUID, request: CreatePriceAlertRequest, by: User): PriceAlertDto {
        requireTelegramActive(householdId)
        // Lock the household row so concurrent creates cannot slip past the cap.
        entityManager.createNativeQuery("SELECT id FROM households WHERE id = :id FOR UPDATE")
            .setParameter("id", householdId)
            .resultList
        if (!households.existsById(householdId)) throw AppException.notFound("HOUSEHOLD_NOT_FOUND")

        val activeCount = alerts.countByHouseholdIdAndStatus(householdId, AlertStatus.ACTIVE)
        if (activeCount >= MAX_ACTIVE_ALERTS_PER_HOUSEHOLD) {
            throw AppException.conflict("PRICE_ALERT_CAP_REACHED", MAX_ACTIVE_ALERTS_PER_HOUSEHOLD)
        }

        val holding = holdings.findByIdAndHouseholdId(request.holdingId, householdId)
            ?: throw AppException.notFound("HOLDING_NOT_FOUND")
        validateHoldingEligible(holding)

        val today = LocalDate.now()
        val now = Instant.now()
        val currentPrice = requireReferencePrice(holding, today)
        val direction = inferDirection(request.threshold, currentPrice, request.expectedDirection)
        val expiresAt = resolveExpiry(request.expiryPreset, request.customExpiryDate, now)
        val note = normalizeNote(request.note)

        val alert = alerts.save(
            PriceAlert(
                householdId = householdId,
                holdingId = holding.id,
                direction = direction,
                threshold = request.threshold.stripTrailingZeros(),
                currency = valuation.priceCurrencyFor(holding),
                expiresAt = expiresAt,
                note = note,
                status = AlertStatus.ACTIVE,
                createdByUserId = by.id,
            )
        )
        log.info(
            "price_alert_created household={} alert={} holding={} direction={} threshold={}",
            householdId, alert.id, holding.id, alert.direction, alert.threshold,
        )
        return toDto(alert, holding, currentPrice, by.email)
    }

    @Transactional
    fun update(householdId: UUID, alertId: UUID, request: UpdatePriceAlertRequest, by: User): PriceAlertDto {
        requireTelegramActive(householdId)
        val now = Instant.now()
        alerts.expireIfDue(alertId, now)
        val alert = alerts.findByIdAndHouseholdId(alertId, householdId)
            ?: throw AppException.notFound("PRICE_ALERT_NOT_FOUND")
        if (alert.status != AlertStatus.ACTIVE) {
            throw AppException.badRequest("PRICE_ALERT_NOT_ACTIVE")
        }

        val holding = holdings.findByIdAndHouseholdId(alert.holdingId, householdId)
            ?: throw AppException.notFound("HOLDING_NOT_FOUND")
        validateHoldingEligible(holding)

        val today = LocalDate.now()
        val currentPrice = requireReferencePrice(holding, today)
        val direction = inferDirection(request.threshold, currentPrice, request.expectedDirection)
        val expiresAt = resolveExpiry(request.expiryPreset, request.customExpiryDate, now)
        val note = normalizeNote(request.note)

        alert.direction = direction
        alert.threshold = request.threshold.stripTrailingZeros()
        alert.expiresAt = expiresAt
        alert.note = note
        alerts.save(alert)

        val creatorEmail = users.findById(alert.createdByUserId).map { it.email }.orElse(by.email)
        log.info("price_alert_updated household={} alert={} direction={} threshold={}", householdId, alert.id, direction, alert.threshold)
        return toDto(alert, holding, currentPrice, creatorEmail)
    }

    @Transactional
    fun cancel(householdId: UUID, alertId: UUID): PriceAlertDto {
        requireTelegramActive(householdId)
        val now = Instant.now()
        alerts.expireIfDue(alertId, now)
        val alert = alerts.findByIdAndHouseholdId(alertId, householdId)
            ?: throw AppException.notFound("PRICE_ALERT_NOT_FOUND")
        if (alert.status != AlertStatus.ACTIVE) {
            throw AppException.badRequest("PRICE_ALERT_NOT_ACTIVE")
        }

        alert.status = AlertStatus.CANCELLED
        alert.cancelledAt = now
        alert.cancelReason = AlertCancelReason.USER
        alerts.save(alert)

        val holding = holdings.findByIdAndHouseholdId(alert.holdingId, householdId)
            ?: throw AppException.notFound("HOLDING_NOT_FOUND")
        val creatorEmail = users.findById(alert.createdByUserId).map { it.email }.orElse(null)
        log.info("price_alert_cancelled household={} alert={} reason=USER", householdId, alert.id)
        return toDto(alert, holding, currentPriceInCurrency(holding, LocalDate.now()), creatorEmail)
    }

    @Transactional
    fun delete(householdId: UUID, alertId: UUID) {
        requireTelegramActive(householdId)
        val now = Instant.now()
        alerts.expireIfDue(alertId, now)
        val alert = alerts.findByIdAndHouseholdId(alertId, householdId)
            ?: throw AppException.notFound("PRICE_ALERT_NOT_FOUND")
        if (alert.status == AlertStatus.ACTIVE) {
            throw AppException.badRequest("PRICE_ALERT_DELETE_ACTIVE_FORBIDDEN")
        }
        alerts.delete(alert)
        log.info("price_alert_deleted household={} alert={}", householdId, alertId)
    }

    private fun validateHoldingEligible(holding: Holding) {
        if (!holding.active || !holding.linked || holding.assetClass == HoldingAssetClass.fund) {
            throw AppException.badRequest("PRICE_ALERT_HOLDING_INELIGIBLE")
        }
        val entries = lots.findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holding.id).map { it.toEntry() }
        val state = PortfolioValuationCalculator.replay(entries, props.portfolio.costMethod)
        if (state.netQuantity.signum() <= 0) {
            throw AppException.badRequest("PRICE_ALERT_HOLDING_CLOSED")
        }
    }

    private fun requireReferencePrice(holding: Holding, today: LocalDate): BigDecimal {
        val provider = holding.provider ?: throw AppException.badRequest("PRICE_ALERT_UNPRICED")
        val providerSymbol = holding.providerSymbol ?: throw AppException.badRequest("PRICE_ALERT_UNPRICED")
        val currency = valuation.priceCurrencyFor(holding)
        val point = prices.findFirstByProviderAndProviderSymbolAndCurrencyAndPriceDateLessThanEqualOrderByPriceDateDesc(
            provider.name, providerSymbol, currency, today,
        ) ?: throw AppException.badRequest("PRICE_ALERT_UNPRICED")
        if (point.priceDate.isBefore(today.minusDays(props.portfolio.stalePriceThresholdDays))) {
            throw AppException.badRequest("PRICE_ALERT_STALE_PRICE")
        }
        return point.price
    }

    private fun currentPriceInCurrency(holding: Holding, today: LocalDate): BigDecimal? {
        val provider = holding.provider ?: return null
        val providerSymbol = holding.providerSymbol ?: return null
        val currency = valuation.priceCurrencyFor(holding)
        return prices.findFirstByProviderAndProviderSymbolAndCurrencyAndPriceDateLessThanEqualOrderByPriceDateDesc(
            provider.name, providerSymbol, currency, today,
        )?.price
    }

    private fun inferDirection(
        threshold: BigDecimal,
        currentPrice: BigDecimal,
        expectedDirection: AlertDirection?,
    ): AlertDirection {
        if (threshold.signum() <= 0) {
            throw AppException.badRequest("PRICE_ALERT_THRESHOLD_INVALID")
        }
        val cmp = threshold.compareTo(currentPrice)
        if (cmp == 0) {
            throw AppException.badRequest("PRICE_ALERT_ALREADY_MET")
        }
        val inferred = if (cmp > 0) AlertDirection.ABOVE else AlertDirection.BELOW
        if (expectedDirection != null && expectedDirection != inferred) {
            throw AppException.badRequest("PRICE_ALERT_ALREADY_MET")
        }
        return inferred
    }

    private fun resolveExpiry(preset: AlertExpiryPreset, customDate: LocalDate?, now: Instant): Instant? = when (preset) {
        AlertExpiryPreset.DAYS_30 -> now.plus(30, ChronoUnit.DAYS)
        AlertExpiryPreset.DAYS_90 -> now.plus(90, ChronoUnit.DAYS)
        AlertExpiryPreset.NO_EXPIRY -> null
        AlertExpiryPreset.CUSTOM_DATE -> {
            val date = customDate ?: throw AppException.badRequest("PRICE_ALERT_EXPIRY_DATE_REQUIRED")
            val zone = props.displayZoneId()
            // Resolve to end-of-day (23:59:59) in the display zone; avoid sub-second 999_999_999 rounding up in Postgres.
            val endOfDay = ZonedDateTime.of(date, LocalTime.of(23, 59, 59), zone).toInstant()
            if (!endOfDay.isAfter(now)) throw AppException.badRequest("PRICE_ALERT_EXPIRY_IN_PAST")
            endOfDay
        }
    }

    private fun normalizeNote(note: String?): String? =
        note?.replace(Regex("[\\r\\n]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(200)

    private fun toDto(
        alert: PriceAlert,
        holding: Holding,
        currentPrice: BigDecimal?,
        createdByEmail: String?,
    ): PriceAlertDto = PriceAlertDto(
        id = alert.id,
        holdingId = alert.holdingId,
        symbol = holding.symbol,
        label = holding.label,
        assetClass = holding.assetClass,
        direction = alert.direction,
        threshold = alert.threshold.stripTrailingZeros(),
        currency = alert.currency,
        currentPrice = currentPrice?.stripTrailingZeros(),
        expiresAt = alert.expiresAt,
        note = alert.note,
        status = alert.status,
        firedAt = alert.firedAt,
        firedPrice = alert.firedPrice?.stripTrailingZeros(),
        firedPriceAsOf = alert.firedPriceAsOf,
        cancelledAt = alert.cancelledAt,
        cancelReason = alert.cancelReason,
        deliverySentAt = alert.deliverySentAt,
        deliveryAttempts = alert.deliveryAttempts,
        deliveryLastAttemptAt = alert.deliveryLastAttemptAt,
        deliveryLastError = alert.deliveryLastError,
        deliveryExhausted = alert.deliverySentAt == null && alert.deliveryAttempts >= MAX_ALERT_DELIVERY_ATTEMPTS,
        createdByEmail = createdByEmail,
        createdAt = alert.createdAt,
    )
}
