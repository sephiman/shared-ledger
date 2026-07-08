package com.sephilabs.sharedledger.networth.amortization

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID


data class PartRequest(
    val label: String? = null,
    @field:NotNull val method: AmortizationMethod = AmortizationMethod.french,
    // current_balance: startDate/principal are today's balance; origin: they are the loan's origin.
    @field:NotNull val startMode: StartMode = StartMode.current_balance,
    @field:NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) val originalPrincipal: BigDecimal,
    @field:NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) val annualRate: BigDecimal = BigDecimal.ZERO,
    // The user provides exactly one of: termMonths, endDate, or instalment (French). The others
    // are computed. endDate is converted to a term on save (relative to startDate).
    val termMonths: Int? = null,
    val endDate: LocalDate? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val instalment: BigDecimal? = null,
    @field:NotNull val startDate: LocalDate,
)

/** Re-anchor an origin-mode part: the real outstanding balance at a date, from which it reprojects. */
data class AnchorRequest(
    @field:NotNull val anchorDate: LocalDate,
    @field:NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) val anchorBalance: BigDecimal,
)

data class PartDto(
    val id: UUID,
    val label: String?,
    val method: AmortizationMethod,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val originalPrincipal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualRate: BigDecimal,
    val termMonths: Int?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val instalment: BigDecimal?,
    val startDate: LocalDate,
)

data class RevisionRequest(
    @field:NotNull val effectiveDate: LocalDate,
    @field:NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) val annualRate: BigDecimal,
)

data class RevisionDto(
    val id: UUID,
    val effectiveDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualRate: BigDecimal,
)

data class PrepaymentRequest(
    @field:NotNull val prepaymentDate: LocalDate,
    @field:NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    @field:NotNull val mode: PrepaymentMode = PrepaymentMode.reduce_term,
)

data class PrepaymentDto(
    val id: UUID,
    val partId: UUID,
    val prepaymentDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    val mode: PrepaymentMode,
)

data class ScheduleRowDto(
    val date: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val interest: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val principal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val balance: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val instalment: BigDecimal,
)

/** An instalment actually charged by the monthly job (distinct from the projection). */
data class ChargedEntryDto(
    val date: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val interest: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val principal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val resultingBalance: BigDecimal,
)

data class PartScheduleDto(
    val partId: UUID,
    val label: String?,
    val method: AmortizationMethod,
    val startMode: StartMode,
    // Raw stored inputs, echoed so the UI can list and edit a part from this one call.
    @JsonFormat(shape = JsonFormat.Shape.STRING) val originalPrincipal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val annualRate: BigDecimal,
    val startDate: LocalDate,
    val termMonths: Int?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val instalmentInput: BigDecimal?,
    val anchorDate: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val anchorBalance: BigDecimal?,
    // Computed from the schedule.
    @JsonFormat(shape = JsonFormat.Shape.STRING) val currentBalance: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val instalment: BigDecimal,
    val payoffDate: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalInterestRemaining: BigDecimal,
    val rows: List<ScheduleRowDto>,
    val charged: List<ChargedEntryDto>,
    val revisions: List<RevisionDto>,
    val prepayments: List<PrepaymentDto>,
)

data class LiabilityScheduleDto(
    val liabilityId: UUID,
    val chargeDay: Int?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val currentBalance: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val monthlyInstalment: BigDecimal,
    // Metrics over the whole loan, computed from the schedule (sum across parts).
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalPrincipal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val interestPaid: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val principalPaid: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val interestRemaining: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalInterest: BigDecimal,
    // Fraction of principal amortised (0..1).
    @JsonFormat(shape = JsonFormat.Shape.STRING) val progress: BigDecimal,
    val parts: List<PartScheduleDto>,
)

data class SimulationRequest(
    @field:NotNull val prepaymentDate: LocalDate,
    @field:NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    @field:NotNull val mode: PrepaymentMode = PrepaymentMode.reduce_term,
)

data class SimulationResultDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val interestSaved: BigDecimal,
    val baselinePayoffDate: LocalDate?,
    val newPayoffDate: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val baselineInstalment: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val newInstalment: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val baselineTotalInterest: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val newTotalInterest: BigDecimal,
)
