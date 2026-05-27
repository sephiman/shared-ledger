package com.sephilabs.sharedledger.budget

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

data class BudgetUpsertItem(
    @field:NotNull
    val year: Short,
    val month: Short? = null,
    @field:NotNull
    val categoryCode: String,
    @field:NotNull
    @field:DecimalMin(value = "0.00", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
)

data class BudgetUpsertRequest(
    @field:Valid
    @field:NotNull
    val items: List<BudgetUpsertItem>,
)

data class BudgetDto(
    val id: UUID,
    val year: Short,
    val month: Short?,
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val updatedByUserId: UUID,
)

data class MonthSummaryRow(
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val budget: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val spent: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val pace: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val projection: BigDecimal,
    val percent: Double,
)

data class MonthSummaryResponse(
    val year: Short,
    val month: Short,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val rows: List<MonthSummaryRow>,
)
