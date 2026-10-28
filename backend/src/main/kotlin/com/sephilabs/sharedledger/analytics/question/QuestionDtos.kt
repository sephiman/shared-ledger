package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.transaction.Direction
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class QuestionRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = QUESTION_MAX_CHARS)
    val question: String,
    val language: QuestionLanguage,
    /** The view whose box was used; the answer stays there unless Explorer cannot show it. */
    val askedFrom: QuestionTarget,
)

/** Null only when the household has no expense category at all. */
data class ExampleCategoryDto(val categoryCode: String?)

data class ScopeDto(val type: ScopeType, val code: String)

/** Mirrors the range selector's value: a preset, or "custom" with either end, or both. */
data class RangeDto(val preset: String, val from: LocalDate?, val to: LocalDate?)

/** One filter object for both views; each view's URL is built from it. A null range is all time; [priorYear] is
 *  Explorer's alone. */
data class QuestionFiltersDto(
    val direction: Direction?,
    val scopes: List<ScopeDto>,
    val range: RangeDto?,
    val isRefund: Boolean,
    val priorYear: Boolean,
)

data class UnsupportedFilterDto(val kind: UnsupportedKind, val label: String)

/** Filters only, never figures. [target] is the app's choice of view and [reason] why it is not the one asked
 *  from; [expressibleIn] the views that could show the filters. Not understood carries nothing to apply. */
data class QuestionAnswerDto(
    val understood: Boolean,
    val target: QuestionTarget?,
    val reason: RouteReason?,
    val expressibleIn: List<QuestionTarget>,
    val filters: QuestionFiltersDto?,
    val unsupported: List<UnsupportedFilterDto>,
)

/** [route] is null when there is nothing to show: not understood, or only unsupported filters were asked for. */
fun Interpretation.toDto(route: QuestionRoute?): QuestionAnswerDto = when (this) {
    is Interpretation.NotUnderstood -> QuestionAnswerDto(false, null, null, emptyList(), null, emptyList())
    is Interpretation.Understood -> QuestionAnswerDto(
        understood = true,
        target = route?.target,
        reason = route?.reason,
        // Fixed order, so the client and the tests read it the same way every time.
        expressibleIn = QuestionTarget.entries.filter { route?.expressibleIn?.contains(it) == true },
        filters = filters?.let {
            QuestionFiltersDto(it.direction, it.scopes.map { s -> s.toDto() }, it.range?.toDto(), it.isRefund, it.priorYear)
        },
        unsupported = unsupported.map { UnsupportedFilterDto(it.kind, it.label) },
    )
}

private fun ScopeRef.toDto() = ScopeDto(type, code)

private fun QuestionRange.toDto() = when (this) {
    is QuestionRange.Preset -> RangeDto(preset, null, null)
    is QuestionRange.Between -> RangeDto("custom", from, to)
}
