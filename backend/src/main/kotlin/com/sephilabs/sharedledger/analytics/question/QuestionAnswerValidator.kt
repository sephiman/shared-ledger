package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.AI_SUGGESTION_MIN_CONFIDENCE
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import java.time.LocalDate

sealed interface Interpretation {
    data class Understood(val filters: QuestionFilters?, val unsupported: List<UnsupportedFilter>) : Interpretation

    data class NotUnderstood(val reason: QuestionRejection) : Interpretation
}

/** Whether a well-formed answer fits this household's catalogue and today's date. Nothing is repaired: any
 *  problem means the question was not understood. */
object QuestionAnswerValidator {

    fun validate(answer: ParsedAnswer.Read, catalogue: List<CatalogueEntry>, today: LocalDate): Interpretation {
        if (answer.confidence < AI_SUGGESTION_MIN_CONFIDENCE) return Interpretation.NotUnderstood(QuestionRejection.low_confidence)
        val problem = when (val filters = answer.filters) {
            null -> QuestionRejection.nothing_asked.takeIf { answer.unsupported.isEmpty() }
            else -> scopeProblem(filters, catalogue) ?: rangeProblem(filters.range, today)
        }
        return problem?.let { Interpretation.NotUnderstood(it) } ?: Interpretation.Understood(answer.filters, answer.unsupported)
    }

    private fun scopeProblem(filters: QuestionFilters, catalogue: List<CatalogueEntry>): QuestionRejection? {
        val direction = filters.direction
        if (filters.isRefund && direction == Direction.income) return QuestionRejection.wrong_direction
        return filters.scopes.firstNotNullOfOrNull { scope ->
            val directions = when (scope.type) {
                ScopeType.category -> listOfNotNull(catalogue.firstOrNull { it.code == scope.code }?.direction)
                ScopeType.group -> catalogue.filter { it.group == scope.code }.map { it.direction }
            }
            when {
                directions.isEmpty() -> QuestionRejection.unknown_category
                direction != null && direction !in directions -> QuestionRejection.wrong_direction
                else -> null
            }
        }
    }

    /** `to` may reach the end of the current month, so "this month" is valid as naturally answered; `from` may not
     *  pass today. */
    private fun rangeProblem(range: QuestionRange?, today: LocalDate): QuestionRejection? {
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        return when (range) {
            null -> null
            is QuestionRange.Preset -> QuestionRejection.invalid_range.takeIf { range.preset !in QuestionSchema.RANGE_PRESETS }
            is QuestionRange.Between -> {
                val (from, to) = range
                when {
                    from != null && to != null && from > to -> QuestionRejection.invalid_range
                    from != null && from > today -> QuestionRejection.future_range
                    to != null && to > monthEnd -> QuestionRejection.future_range
                    from != null && to != null && from < to.minusYears(QuestionSchema.MAX_SPAN_YEARS) -> QuestionRejection.invalid_range
                    else -> null
                }
            }
        }
    }
}
