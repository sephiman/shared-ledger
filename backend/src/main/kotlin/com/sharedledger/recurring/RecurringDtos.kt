package com.sharedledger.recurring

import com.fasterxml.jackson.annotation.JsonFormat
import com.sharedledger.transaction.Direction
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RecurringTemplateRequest(
    @field:NotNull val direction: Direction,
    @field:NotNull val categoryCode: String,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String? = null,
    @field:NotNull val cadence: Cadence,
    val dayOfMonth: Short? = null,
    val dayOfWeek: Short? = null,
    val monthOfYear: Short? = null,
    val dayOfMonthYearly: Short? = null,
    @field:NotNull val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val active: Boolean = true,
)

data class RecurringTemplateDto(
    val id: UUID,
    val direction: Direction,
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String?,
    val cadence: Cadence,
    val dayOfMonth: Short?,
    val dayOfWeek: Short?,
    val monthOfYear: Short?,
    val dayOfMonthYearly: Short?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val active: Boolean,
    val lastMaterializedThrough: LocalDate?,
    val nextFireDate: LocalDate?,
)

fun RecurringTemplate.toDto(next: LocalDate?): RecurringTemplateDto = RecurringTemplateDto(
    id = id,
    direction = direction,
    categoryCode = categoryCode,
    amount = amount,
    description = description,
    cadence = cadence,
    dayOfMonth = dayOfMonth,
    dayOfWeek = dayOfWeek,
    monthOfYear = monthOfYear,
    dayOfMonthYearly = dayOfMonthYearly,
    startDate = startDate,
    endDate = endDate,
    active = active,
    lastMaterializedThrough = lastMaterializedThrough,
    nextFireDate = next,
)
