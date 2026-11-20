package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.ai.AiGroundingVerifier
import com.sephilabs.sharedledger.analytics.question.ProjectionQuestionParser
import com.sephilabs.sharedledger.analytics.question.ProjectionQuestionValidator
import com.sephilabs.sharedledger.analytics.question.ProjectionScenario
import com.sephilabs.sharedledger.analytics.question.ScopeType
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.catalog.CategoryView
import tools.jackson.databind.JsonNode

/** Grounding verifier for Analytics AI follow-up answers and safety validator for Money flow scenarios.
 *  Finding selection, ordering, actionability, and figure formatting are handled deterministically by
 *  [SpendingReviewSelector] and [SpendingReviewRenderer]. */
object AnalyticsAiReviewVerifier {

    fun verifyFollowupCitations(payload: JsonNode, citations: JsonNode): AiGroundingVerifier.Result =
        AiGroundingVerifier.verifyCitations(payload, citations)

    /** Validates a [ProjectionScenario] with the sandbox's own validator and refuses cuts/freezes on essentials. */
    fun sanitizeScenario(
        node: JsonNode?,
        catalogue: List<CatalogueEntry>,
        catByCode: Map<String, CategoryView>,
    ): ProjectionScenario? {
        val parsed = ProjectionQuestionParser.parseScenario(node) ?: return null
        if (ProjectionQuestionValidator.validateScenario(parsed, catalogue) != null) return null
        val essentialGroupCodes = catByCode.values
            .groupBy { it.group }
            .filter { (g, list) -> g != null && g != "financial" && list.all { it.essential } }
            .keys

        fun isForbiddenEssentialScope(type: ScopeType, code: String): Boolean = when (type) {
            ScopeType.category -> {
                val cat = catByCode[code]
                cat != null && cat.essential && code != "financial.fees" && cat.group != "financial"
            }
            ScopeType.group -> code in essentialGroupCodes
        }

        if (parsed.freeze.any { isForbiddenEssentialScope(it.type, it.code) }) return null
        if (parsed.adjustments.any { it.value.signum() < 0 && isForbiddenEssentialScope(it.scope.type, it.scope.code) }) {
            return null
        }
        return parsed
    }
}
