package com.sephilabs.sharedledger.transaction

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** [amount] carries no bean-validated floor because the rule depends on [isRefund] — positive for an
 *  ordinary transaction, negative for a refund. [TransactionService] enforces both, so every writer that
 *  goes through it is covered. */
data class TransactionRequest(
    @field:NotNull(message = "validation.required")
    val occurrenceDate: LocalDate,

    @field:NotNull(message = "validation.required")
    val direction: Direction,

    @field:NotNull(message = "validation.required")
    val categoryCode: String,

    @field:NotNull(message = "validation.required")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,

    @field:Size(max = 500)
    val description: String? = null,

    /** A negative expense: money coming back for a past purchase. */
    val isRefund: Boolean = false,

    /** Optional; the expense this refund nets against. */
    val refundOfTransactionId: UUID? = null,
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
    // Without this the getter's `is` prefix is stripped and the field ships as "refund".
    @get:JsonProperty("isRefund")
    val isRefund: Boolean = false,
    val refundOfTransactionId: UUID? = null,
    /** The linked original, resolved for the list. Null on a refund whose original was since deleted. */
    val refundOf: RefundOfSummary? = null,
    /** On an original: the signed (negative) sum of the refunds linked to it, null when there are none. */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val refundedTotal: BigDecimal? = null,
    val refundCount: Int? = null,
)

/** Just enough of the original expense to name and link it from the refund's row. */
data class RefundOfSummary(
    val id: UUID,
    val occurrenceDate: LocalDate,
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String?,
)

fun Transaction.toDto(
    refundOf: RefundOfSummary? = null,
    refundedTotal: BigDecimal? = null,
    refundCount: Int? = null,
): TransactionDto = TransactionDto(
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
    isRefund = isRefund,
    refundOfTransactionId = refundOfTransactionId,
    refundOf = refundOf,
    refundedTotal = refundedTotal,
    refundCount = refundCount,
)

fun Transaction.toRefundOfSummary(): RefundOfSummary =
    RefundOfSummary(id, occurrenceDate, categoryCode, amount, description)

data class QuickChip(
    val categoryCode: String,
    val count: Long,
)
