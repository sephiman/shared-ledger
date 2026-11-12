package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import java.math.BigDecimal

enum class AdjustmentKind { amount, percent }

data class ProjectionAdjustment(
    val scope: ScopeRef,
    val kind: AdjustmentKind,
    val value: BigDecimal,
)

data class ProjectionScenario(
    val freeze: List<ScopeRef>,
    val adjustments: List<ProjectionAdjustment>,
    val level: String?,
    val windowMonths: Int?,
    val modeOverride: ProjectionMode?,
    val savingsTarget: BigDecimal?,
) {
    fun isEmpty(): Boolean =
        freeze.isEmpty() &&
            adjustments.isEmpty() &&
            level == null &&
            windowMonths == null &&
            modeOverride == null &&
            savingsTarget == null
}

enum class ExplainKind { node, recurring_due, top_expenses, deficit_drivers, outlook }

data class ProjectionExplain(
    val kind: ExplainKind,
    val scope: ScopeRef?,
)

object ProjectionQuestionSchema {
    const val VERSION = "pq1"
    const val MIN_WINDOW_MONTHS = 1
    const val MAX_WINDOW_MONTHS = 24
    val LEVELS = setOf("group", "category")
}
