package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.analytics.projectionmode.ProjectionMode
import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

sealed interface ParsedProjectionAnswer {
    data class Read(
        val scenario: ProjectionScenario?,
        val explain: ProjectionExplain?,
        val unsupported: List<UnsupportedFilter>,
        val confidence: Double,
    ) : ParsedProjectionAnswer

    data class Unreadable(val reason: QuestionRejection) : ParsedProjectionAnswer
}

object ProjectionQuestionParser {

    private class Refused(val reason: QuestionRejection) : RuntimeException(null, null, false, false)

    fun parse(raw: String?): ParsedProjectionAnswer {
        val root = JsonObjectReader.first(raw) ?: return ParsedProjectionAnswer.Unreadable(QuestionRejection.unreadable)
        return try {
            read(root)
        } catch (refused: Refused) {
            ParsedProjectionAnswer.Unreadable(refused.reason)
        }
    }

    private fun read(root: JsonNode): ParsedProjectionAnswer.Read {
        val confidence = root.path("confidence").takeIf { it.isNumber }?.asDouble()?.takeIf { it in 0.0..1.0 }
            ?: refuse(QuestionRejection.malformed)
        val unsupported = unsupported(root.path("unsupported"))
        val scenario = scenario(root.path("scenario"))
        val explain = explain(root.path("explain"))
        return ParsedProjectionAnswer.Read(scenario, explain, unsupported, confidence)
    }

    private fun scenario(node: JsonNode): ProjectionScenario? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isObject) refuse(QuestionRejection.malformed)
        val freeze = scopeArray(node.path("freeze"))
        val adjustments = adjustmentArray(node.path("adjustments"))
        val level = optionalString(node.path("level"))
        val windowMonths = optionalInt(node.path("windowMonths"))
        val modeOverride = optionalString(node.path("modeOverride"))?.let { name ->
            ProjectionMode.entries.firstOrNull { it.name == name } ?: refuse(QuestionRejection.malformed)
        }
        val savingsTarget = optionalDecimal(node.path("savingsTarget"))
        val parsed = ProjectionScenario(freeze, adjustments, level, windowMonths, modeOverride, savingsTarget)
        return parsed.takeUnless { it.isEmpty() }
    }

    private fun explain(node: JsonNode): ProjectionExplain? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isObject) refuse(QuestionRejection.malformed)
        val kindName = optionalString(node.path("kind")) ?: refuse(QuestionRejection.malformed)
        val kind = ExplainKind.entries.firstOrNull { it.name == kindName } ?: refuse(QuestionRejection.malformed)
        val scopeNode = node.path("scope")
        val scope = if (scopeNode.isMissingNode || scopeNode.isNull) null else scope(scopeNode)
        return ProjectionExplain(kind, scope)
    }

    private fun scopeArray(node: JsonNode): List<ScopeRef> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) refuse(QuestionRejection.malformed)
        return node.values().map(::scope).distinct()
    }

    private fun adjustmentArray(node: JsonNode): List<ProjectionAdjustment> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) refuse(QuestionRejection.malformed)
        return node.values().map(::adjustment)
    }

    private fun adjustment(node: JsonNode): ProjectionAdjustment {
        if (!node.isObject) refuse(QuestionRejection.malformed)
        val scope = scope(node.path("scope"))
        val kindName = optionalString(node.path("kind")) ?: refuse(QuestionRejection.malformed)
        val kind = AdjustmentKind.entries.firstOrNull { it.name == kindName } ?: refuse(QuestionRejection.malformed)
        val valueNode = node.path("value")
        val value = if (valueNode.isNumber) valueNode.decimalValue() else refuse(QuestionRejection.malformed)
        return ProjectionAdjustment(scope, kind, value)
    }

    private fun scope(node: JsonNode): ScopeRef {
        if (!node.isObject) refuse(QuestionRejection.malformed)
        val type = optionalString(node.path("type"))?.let { name -> ScopeType.entries.firstOrNull { it.name == name } }
        val code = optionalString(node.path("code"))
        if (type == null || code == null) refuse(QuestionRejection.malformed)
        return ScopeRef(type, code)
    }

    private fun unsupported(node: JsonNode): List<UnsupportedFilter> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) refuse(QuestionRejection.malformed)
        return node.values().take(QuestionSchema.MAX_UNSUPPORTED).map { item ->
            if (!item.isObject) refuse(QuestionRejection.malformed)
            val kind = optionalString(item.path("kind"))?.let { name -> UnsupportedKind.entries.firstOrNull { it.name == name } }
                ?: UnsupportedKind.other
            val label = optionalString(item.path("label"))?.take(QuestionSchema.MAX_UNSUPPORTED_LABEL)
                ?: refuse(QuestionRejection.malformed)
            UnsupportedFilter(kind, label)
        }
    }

    private fun optionalString(node: JsonNode): String? =
        if (node.isMissingNode || node.isNull) null
        else node.takeIf { it.isString }?.asString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun optionalInt(node: JsonNode): Int? =
        if (node.isMissingNode || node.isNull) null
        else if (node.isInt || node.isLong) node.asInt() else refuse(QuestionRejection.malformed)

    private fun optionalDecimal(node: JsonNode): BigDecimal? =
        if (node.isMissingNode || node.isNull) null
        else if (node.isNumber) node.decimalValue() else refuse(QuestionRejection.malformed)

    private fun refuse(reason: QuestionRejection): Nothing = throw Refused(reason)
}
