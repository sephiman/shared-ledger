package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.ai.AiGroundingVerifier
import tools.jackson.databind.JsonNode

/** Delegates FIRE plan-review grounding checks to the shared [AiGroundingVerifier]. */
object FireAiGroundingVerifier {

    private val SECTIONS = listOf("strengths", "improvements", "suggestedWhatIfs", "observations", "dataGaps")

    fun verify(payload: JsonNode, answer: JsonNode): AiGroundingVerifier.Result =
        AiGroundingVerifier.verifySections(payload, answer, SECTIONS)

    fun verifyCitations(payload: JsonNode, citations: JsonNode): AiGroundingVerifier.Result =
        AiGroundingVerifier.verifyCitations(payload, citations)

    fun check(payload: JsonNode, citation: JsonNode): AiGroundingVerifier.CitationCheck =
        AiGroundingVerifier.check(payload, citation)

    fun resolve(payload: JsonNode, path: String): JsonNode? =
        AiGroundingVerifier.resolve(payload, path)
}
