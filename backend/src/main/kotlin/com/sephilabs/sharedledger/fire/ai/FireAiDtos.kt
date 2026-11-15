package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.fire.FirePreviewRequest
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class FireAiFollowupRequest(val question: String?)

data class FireAiCitationDto(
    val path: String,
    val value: JsonNode?,
    val grounded: Boolean,
    val context: String? = null,
)

data class FireAiScopeDto(
    val referenceTier: String,
    val referenceScenarioIndex: Int,
    val referenceScenarioName: String,
    val snapshotDate: LocalDate?,
    val coveragePercent: Double?,
    val estimatedYear: Int?,
    val probability: Double?,
)

data class FireAiStrengthDto(
    val title: String,
    val detail: String,
    val citations: List<FireAiCitationDto>,
)

data class FireAiImprovementDto(
    val title: String,
    val detail: String,
    val severity: String?,
    val citations: List<FireAiCitationDto>,
)

data class FireAiSuggestedWhatIfDto(
    val title: String,
    val rationale: String,
    val expectedImpact: String?,
    val preview: FirePreviewRequest?,
    val citations: List<FireAiCitationDto>,
)

data class FireAiObservationDto(
    val claim: String,
    val confidence: String?,
    val citations: List<FireAiCitationDto>,
)

data class FireAiDataGapDto(
    val topic: String,
    val reason: String,
    val citations: List<FireAiCitationDto>,
)

data class FireAiFollowupDto(
    val id: UUID,
    val question: String,
    val answer: String,
    val citations: List<FireAiCitationDto>,
    val citationsTotal: Int,
    val citationsUngrounded: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    val createdAt: Instant,
)

data class FireAiRunDto(
    val id: UUID,
    val status: FireAiAnalysisRunStatus,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val scope: FireAiScopeDto,
    val model: String,
    val promptVersion: String,
    val summary: String?,
    val evolution: String?,
    val strengths: List<FireAiStrengthDto>,
    val improvements: List<FireAiImprovementDto>,
    val suggestedWhatIfs: List<FireAiSuggestedWhatIfDto>,
    val observations: List<FireAiObservationDto>,
    val dataGaps: List<FireAiDataGapDto>,
    val observationsCount: Int?,
    val dataGapsCount: Int?,
    val citationsTotal: Int?,
    val citationsUngrounded: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val latencyMs: Long?,
    val errorCode: String?,
    val followups: List<FireAiFollowupDto> = emptyList(),
)
