package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.AI_SUGGESTION_MIN_CONFIDENCE
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import java.math.BigDecimal

sealed interface ProjectionInterpretation {
    data class Understood(
        val scenario: ProjectionScenario?,
        val explain: ProjectionExplain?,
        val unsupported: List<UnsupportedFilter>,
    ) : ProjectionInterpretation

    data class NotUnderstood(val reason: QuestionRejection) : ProjectionInterpretation
}

object ProjectionQuestionValidator {

    fun validate(answer: ParsedProjectionAnswer.Read, catalogue: List<CatalogueEntry>): ProjectionInterpretation {
        if (answer.confidence < AI_SUGGESTION_MIN_CONFIDENCE) {
            return ProjectionInterpretation.NotUnderstood(QuestionRejection.low_confidence)
        }
        val problem = when {
            answer.scenario == null && answer.explain == null && answer.unsupported.isEmpty() ->
                QuestionRejection.nothing_asked
            else -> scenarioProblem(answer.scenario, catalogue) ?: explainProblem(answer.explain, catalogue)
        }
        return problem?.let { ProjectionInterpretation.NotUnderstood(it) }
            ?: ProjectionInterpretation.Understood(answer.scenario, answer.explain, answer.unsupported)
    }

    private fun scenarioProblem(scenario: ProjectionScenario?, catalogue: List<CatalogueEntry>): QuestionRejection? {
        if (scenario == null) return null
        if (scenario.level != null && scenario.level !in ProjectionQuestionSchema.LEVELS) return QuestionRejection.malformed
        if (scenario.windowMonths != null &&
            scenario.windowMonths !in ProjectionQuestionSchema.MIN_WINDOW_MONTHS..ProjectionQuestionSchema.MAX_WINDOW_MONTHS
        ) {
            return QuestionRejection.invalid_range
        }
        if (scenario.savingsTarget != null && scenario.savingsTarget < BigDecimal.ZERO) {
            return QuestionRejection.malformed
        }
        val scopes = scenario.freeze + scenario.adjustments.map { it.scope }
        return scopes.firstNotNullOfOrNull { scopeProblem(it, catalogue) }
    }

    private fun explainProblem(explain: ProjectionExplain?, catalogue: List<CatalogueEntry>): QuestionRejection? {
        if (explain == null) return null
        if (explain.kind == ExplainKind.node && explain.scope == null) return QuestionRejection.malformed
        return explain.scope?.let { scopeProblem(it, catalogue) }
    }

    private fun scopeProblem(scope: ScopeRef, catalogue: List<CatalogueEntry>): QuestionRejection? {
        val exists = when (scope.type) {
            ScopeType.category -> catalogue.any { it.code == scope.code }
            ScopeType.group -> catalogue.any { it.group == scope.code }
        }
        return if (exists) null else QuestionRejection.unknown_category
    }
}
