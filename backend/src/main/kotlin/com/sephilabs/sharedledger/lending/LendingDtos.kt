package com.sephilabs.sharedledger.lending

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class LendingRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val borrowerName: String,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val principalAmount: BigDecimal,
    @field:NotNull val startDate: LocalDate,
    @field:Size(max = 500) val description: String? = null,
    @field:NotNull val interestType: InterestType,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val annualInterestRate: BigDecimal? = null,
    val compoundingPeriod: CompoundingPeriod? = null,
)

data class LendingStatusTransitionRequest(
    val closedDate: LocalDate? = null,
)

data class LendingPaymentRequest(
    @field:NotNull val paymentDate: LocalDate,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    @field:Size(max = 500) val description: String? = null,
)

data class LendingScheduleRequest(
    @field:NotNull val frequency: LendingFrequency,
    val dayOfWeek: Short? = null,
    val dayOfMonth: Short? = null,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val expectedAmount: BigDecimal,
    val active: Boolean = true,
)

data class LendingPaymentDto(
    val id: UUID,
    val lendingId: UUID,
    val paymentDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String?,
    val scheduleId: UUID?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val interestPaid: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val principalPaid: BigDecimal,
)

data class LendingScheduleDto(
    val id: UUID,
    val lendingId: UUID,
    val frequency: LendingFrequency,
    val dayOfWeek: Short?,
    val dayOfMonth: Short?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val expectedAmount: BigDecimal,
    val active: Boolean,
    val lastMaterializedThrough: LocalDate?,
)

data class LendingSummary(
    val id: UUID,
    val borrowerName: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val principalAmount: BigDecimal,
    val startDate: LocalDate,
    val description: String?,
    val interestType: InterestType,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val annualInterestRate: BigDecimal?,
    val compoundingPeriod: CompoundingPeriod?,
    val status: LendingStatus,
    val closedDate: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val principalRemaining: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val accruedInterest: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalOutstanding: BigDecimal,
    val hasSchedule: Boolean,
    val scheduleActive: Boolean,
)

data class LendingDetail(
    val summary: LendingSummary,
    val payments: List<LendingPaymentDto>,
    val schedule: LendingScheduleDto?,
)

data class LendingListResponse(
    val lendings: List<LendingSummary>,
    val totalOutstandingActive: BigDecimal,
    val activeCount: Int,
    val top: List<LendingSummary>,
)

data class PaymentSplitPreview(
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val interestPaid: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val principalPaid: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val accruedInterestBefore: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val principalBefore: BigDecimal,
)
