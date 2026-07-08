package com.sephilabs.sharedledger.networth.movement

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class MovementRequest(
    @field:NotNull val movementDate: LocalDate,
    @field:NotNull val type: MovementType,
    val assetClassCode: String? = null,
    val liabilityId: UUID? = null,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String? = null,
)

data class MovementDto(
    val id: UUID,
    val movementDate: LocalDate,
    val type: MovementType,
    val assetClassCode: String?,
    val liabilityId: UUID?,
    // Resolved name (incl. soft-deleted liabilities) so the UI never shows a raw UUID.
    val liabilityName: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String?,
    val createdByUserId: UUID,
    val updatedByUserId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun NetWorthMovement.toDto(liabilityName: String? = null) = MovementDto(
    id, movementDate, type, assetClassCode, liabilityId, liabilityName,
    amount, description, createdByUserId, updatedByUserId, createdAt, updatedAt,
)

data class CumulativeBucket(
    val key: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val contributions: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val withdrawals: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val debtPayments: BigDecimal,
)
