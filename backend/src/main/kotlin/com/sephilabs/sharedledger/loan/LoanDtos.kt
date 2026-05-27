package com.sephilabs.sharedledger.loan

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class LoanRequest(
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

data class LoanStatusTransitionRequest(
    val closedDate: LocalDate? = null,
)

data class LoanPaymentRequest(
    @field:NotNull val paymentDate: LocalDate,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    @field:Size(max = 500) val description: String? = null,
)

data class LoanScheduleRequest(
    @field:NotNull val frequency: LoanFrequency,
    val dayOfWeek: Short? = null,
    val dayOfMonth: Short? = null,
    @field:NotNull
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val expectedAmount: BigDecimal,
    val active: Boolean = true,
)

data class LoanPaymentDto(
    val id: UUID,
    val loanId: UUID,
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

data class LoanScheduleDto(
    val id: UUID,
    val loanId: UUID,
    val frequency: LoanFrequency,
    val dayOfWeek: Short?,
    val dayOfMonth: Short?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val expectedAmount: BigDecimal,
    val active: Boolean,
    val lastMaterializedThrough: LocalDate?,
)

data class LoanSummary(
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
    val status: LoanStatus,
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

data class LoanDetail(
    val summary: LoanSummary,
    val payments: List<LoanPaymentDto>,
    val schedule: LoanScheduleDto?,
)

data class LoanListResponse(
    val loans: List<LoanSummary>,
    val totalOutstandingActive: BigDecimal,
    val activeCount: Int,
    val top: List<LoanSummary>,
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
