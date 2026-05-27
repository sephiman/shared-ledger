package com.sephilabs.sharedledger.transaction

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class TransactionRequest(
    @field:NotNull(message = "validation.required")
    val occurrenceDate: LocalDate,

    @field:NotNull(message = "validation.required")
    val direction: Direction,

    @field:NotNull(message = "validation.required")
    val categoryCode: String,

    @field:NotNull(message = "validation.required")
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,

    @field:Size(max = 500)
    val description: String? = null,
)

data class TransactionDto(
    val id: UUID,
    val occurrenceDate: LocalDate,
    val direction: Direction,
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String?,
    val recurringTemplateId: UUID?,
    val createdByUserId: UUID,
    val updatedByUserId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun Transaction.toDto(): TransactionDto = TransactionDto(
    id = id,
    occurrenceDate = occurrenceDate,
    direction = direction,
    categoryCode = categoryCode,
    amount = amount,
    description = description,
    recurringTemplateId = recurringTemplateId,
    createdByUserId = createdByUserId,
    updatedByUserId = updatedByUserId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

data class QuickChip(
    val categoryCode: String,
    val count: Long,
)
