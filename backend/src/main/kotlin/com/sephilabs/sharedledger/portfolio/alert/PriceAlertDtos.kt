package com.sephilabs.sharedledger.portfolio.alert

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AlertExpiryPreset { DAYS_30, DAYS_90, CUSTOM_DATE, NO_EXPIRY }

data class CreatePriceAlertRequest(
    @field:NotNull val holdingId: UUID,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @field:NotNull val threshold: BigDecimal,
    val expectedDirection: AlertDirection? = null,
    val expiryPreset: AlertExpiryPreset = AlertExpiryPreset.DAYS_90,
    val customExpiryDate: LocalDate? = null,
    @field:Size(max = 200) val note: String? = null,
)

data class UpdatePriceAlertRequest(
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @field:NotNull val threshold: BigDecimal,
    val expectedDirection: AlertDirection? = null,
    val expiryPreset: AlertExpiryPreset = AlertExpiryPreset.DAYS_90,
    val customExpiryDate: LocalDate? = null,
    @field:Size(max = 200) val note: String? = null,
)

data class PriceAlertDto(
    val id: UUID,
    val holdingId: UUID,
    val symbol: String,
    val label: String?,
    val assetClass: HoldingAssetClass,
    val direction: AlertDirection,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val threshold: BigDecimal,
    val currency: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val currentPrice: BigDecimal?,
    val expiresAt: Instant?,
    val note: String?,
    val status: AlertStatus,
    val firedAt: Instant?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val firedPrice: BigDecimal?,
    val firedPriceAsOf: Instant?,
    val cancelledAt: Instant?,
    val cancelReason: AlertCancelReason?,
    val deliverySentAt: Instant?,
    val deliveryAttempts: Int,
    val deliveryLastAttemptAt: Instant?,
    val deliveryLastError: String?,
    val deliveryExhausted: Boolean,
    val createdByEmail: String?,
    val createdAt: Instant,
)
