package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class ProjectionQuestionRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = QUESTION_MAX_CHARS)
    val question: String,
    val language: QuestionLanguage,
)

data class ProjectionAdjustmentDto(
    val scope: ScopeDto,
    val kind: AdjustmentKind,
    val value: BigDecimal,
)

data class ProjectionScenarioDto(
    val freeze: List<ScopeDto>,
    val adjustments: List<ProjectionAdjustmentDto>,
    val level: String?,
    val windowMonths: Int?,
    val modeOverride: ProjectionMode?,
    val savingsTarget: BigDecimal?,
)

data class ProjectionExplainDto(
    val kind: ExplainKind,
    val scope: ScopeDto?,
)

data class ProjectionQuestionAnswerDto(
    val understood: Boolean,
    val scenario: ProjectionScenarioDto?,
    val explain: ProjectionExplainDto?,
    val unsupported: List<UnsupportedFilterDto>,
)

fun ProjectionInterpretation.toDto(): ProjectionQuestionAnswerDto = when (this) {
    is ProjectionInterpretation.NotUnderstood -> ProjectionQuestionAnswerDto(false, null, null, emptyList())
    is ProjectionInterpretation.Understood -> ProjectionQuestionAnswerDto(
        understood = true,
        scenario = scenario?.let { s ->
            ProjectionScenarioDto(
                freeze = s.freeze.map { ScopeDto(it.type, it.code) },
                adjustments = s.adjustments.map { a ->
                    ProjectionAdjustmentDto(ScopeDto(a.scope.type, a.scope.code), a.kind, a.value)
                },
                level = s.level,
                windowMonths = s.windowMonths,
                modeOverride = s.modeOverride,
                savingsTarget = s.savingsTarget,
            )
        },
        explain = explain?.let { e -> ProjectionExplainDto(e.kind, e.scope?.let { ScopeDto(it.type, it.code) }) },
        unsupported = unsupported.map { UnsupportedFilterDto(it.kind, it.label) },
    )
}
