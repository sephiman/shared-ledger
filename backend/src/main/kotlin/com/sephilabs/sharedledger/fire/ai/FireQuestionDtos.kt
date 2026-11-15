package com.sephilabs.sharedledger.fire.ai

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.analytics.question.QUESTION_MAX_CHARS
import com.sephilabs.sharedledger.analytics.question.QuestionLanguage
import com.sephilabs.sharedledger.fire.CashFlowEventKind
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class FireQuestionRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = QUESTION_MAX_CHARS)
    val question: String,
    val language: QuestionLanguage,
)

data class FireDecimalChangeDto(
    val kind: FireAdjustmentKind,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val value: BigDecimal,
)

data class FireYearChangeDto(
    val kind: FireYearAdjustmentKind,
    val value: Int,
)

data class FireQuestionEventDto(
    val year: Int,
    val kind: CashFlowEventKind,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    val label: String,
    val template: String?,
)

data class FireQuestionWhatIfDto(
    val monthlyContribution: FireDecimalChangeDto?,
    val monthlySpending: FireDecimalChangeDto?,
    val targetYear: FireYearChangeDto?,
    val safeWithdrawalRatePct: FireDecimalChangeDto?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val lumpSum: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val returnShiftPct: BigDecimal?,
    val cashFlowEvents: List<FireQuestionEventDto>,
)

data class FireQuestionUnsupportedDto(val kind: FireQuestionUnsupportedKind, val label: String)

data class FireQuestionAnswerDto(
    val understood: Boolean,
    val whatIf: FireQuestionWhatIfDto?,
    val unsupported: List<FireQuestionUnsupportedDto>,
)

fun FireQuestionInterpretation.toDto(): FireQuestionAnswerDto = when (this) {
    is FireQuestionInterpretation.NotUnderstood -> FireQuestionAnswerDto(false, null, emptyList())
    is FireQuestionInterpretation.Understood -> FireQuestionAnswerDto(
        understood = true,
        whatIf = whatIf?.let { w ->
            FireQuestionWhatIfDto(
                monthlyContribution = w.monthlyContribution?.let { FireDecimalChangeDto(it.kind, it.value) },
                monthlySpending = w.monthlySpending?.let { FireDecimalChangeDto(it.kind, it.value) },
                targetYear = w.targetYear?.let { FireYearChangeDto(it.kind, it.value) },
                safeWithdrawalRatePct = w.safeWithdrawalRatePct?.let { FireDecimalChangeDto(it.kind, it.value) },
                lumpSum = w.lumpSum,
                returnShiftPct = w.returnShiftPct,
                cashFlowEvents = w.cashFlowEvents.map {
                    FireQuestionEventDto(it.year, it.kind, it.amount, it.label, it.template)
                },
            )
        },
        unsupported = unsupported.map { FireQuestionUnsupportedDto(it.kind, it.label) },
    )
}
