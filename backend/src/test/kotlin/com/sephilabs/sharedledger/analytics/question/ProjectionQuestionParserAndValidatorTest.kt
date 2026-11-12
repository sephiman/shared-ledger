package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProjectionQuestionParserAndValidatorTest {

    private val catalogue = listOf(
        CatalogueEntry("food.groceries", "Groceries", "food", Direction.expense, true),
        CatalogueEntry("outings.dining", "Dining", "outings", Direction.expense, false),
        CatalogueEntry("income.salary", "Salary", null, Direction.income, true),
    )

    @Test
    fun `parses scenario with freeze, adjustments, mode override, and savings target`() {
        val raw = """
            {
              "scenario": {
                "freeze": [{"type": "group", "code": "outings"}],
                "adjustments": [
                  {"scope": {"type": "category", "code": "food.groceries"}, "kind": "percent", "value": -20},
                  {"scope": {"type": "category", "code": "income.salary"}, "kind": "amount", "value": 500}
                ],
                "level": "category",
                "windowMonths": 12,
                "modeOverride": "PRORATE",
                "savingsTarget": 400
              },
              "explain": null,
              "unsupported": [],
              "confidence": 0.92
            }
        """.trimIndent()

        val parsed = ProjectionQuestionParser.parse(raw) as ParsedProjectionAnswer.Read
        val validated = ProjectionQuestionValidator.validate(parsed, catalogue) as ProjectionInterpretation.Understood

        assertThat(validated.scenario).isEqualTo(
            ProjectionScenario(
                freeze = listOf(ScopeRef(ScopeType.group, "outings")),
                adjustments = listOf(
                    ProjectionAdjustment(ScopeRef(ScopeType.category, "food.groceries"), AdjustmentKind.percent, BigDecimal("-20")),
                    ProjectionAdjustment(ScopeRef(ScopeType.category, "income.salary"), AdjustmentKind.amount, BigDecimal("500")),
                ),
                level = "category",
                windowMonths = 12,
                modeOverride = ProjectionMode.PRORATE,
                savingsTarget = BigDecimal("400"),
            ),
        )
        assertThat(validated.explain).isNull()
    }

    @Test
    fun `parses explain request for a specific node`() {
        val raw = """
            {
              "scenario": null,
              "explain": {"kind": "node", "scope": {"type": "group", "code": "food"}},
              "unsupported": [],
              "confidence": 0.9
            }
        """.trimIndent()

        val parsed = ProjectionQuestionParser.parse(raw) as ParsedProjectionAnswer.Read
        val validated = ProjectionQuestionValidator.validate(parsed, catalogue) as ProjectionInterpretation.Understood

        assertThat(validated.scenario).isNull()
        assertThat(validated.explain).isEqualTo(ProjectionExplain(ExplainKind.node, ScopeRef(ScopeType.group, "food")))
    }

    @Test
    fun `rejects unknown category or low confidence`() {
        val unknown = """
            {"scenario": {"freeze": [{"type": "category", "code": "nope"}], "adjustments": []}, "explain": null, "unsupported": [], "confidence": 0.95}
        """.trimIndent()
        val low = """
            {"scenario": null, "explain": {"kind": "outlook", "scope": null}, "unsupported": [], "confidence": 0.2}
        """.trimIndent()

        assertThat(
            ProjectionQuestionValidator.validate(ProjectionQuestionParser.parse(unknown) as ParsedProjectionAnswer.Read, catalogue),
        ).isEqualTo(ProjectionInterpretation.NotUnderstood(QuestionRejection.unknown_category))

        assertThat(
            ProjectionQuestionValidator.validate(ProjectionQuestionParser.parse(low) as ParsedProjectionAnswer.Read, catalogue),
        ).isEqualTo(ProjectionInterpretation.NotUnderstood(QuestionRejection.low_confidence))
    }
}
