package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import com.sephilabs.sharedledger.transaction.Direction
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class QuestionRejection {
    unreadable, malformed, unknown_category, wrong_direction, invalid_range, future_range, low_confidence, nothing_asked,
}

sealed interface ParsedAnswer {
    data class Read(
        val filters: QuestionFilters?,
        val unsupported: List<UnsupportedFilter>,
        val confidence: Double,
    ) : ParsedAnswer

    data class Unreadable(val reason: QuestionRejection) : ParsedAnswer
}

/** Shape only: whether the codes and dates make sense for this household today is [QuestionAnswerValidator]'s job. */
object QuestionAnswerParser {

    private class Refused(val reason: QuestionRejection) : RuntimeException(null, null, false, false)

    fun parse(raw: String?): ParsedAnswer {
        val root = JsonObjectReader.first(raw) ?: return ParsedAnswer.Unreadable(QuestionRejection.unreadable)
        return try {
            read(root)
        } catch (refused: Refused) {
            ParsedAnswer.Unreadable(refused.reason)
        }
    }

    /** Any "target" the model adds is ignored: which view shows the filters is the app's decision. */
    private fun read(root: JsonNode): ParsedAnswer.Read {
        val confidence = root.path("confidence").takeIf { it.isNumber }?.asDouble()?.takeIf { it in 0.0..1.0 }
            ?: refuse(QuestionRejection.malformed)
        val unsupported = unsupported(root.path("unsupported"))
        val filtersNode = root.path("filters")
        if (filtersNode.isMissingNode) refuse(QuestionRejection.malformed)
        if (filtersNode.isNull) return ParsedAnswer.Read(null, unsupported, confidence)
        if (!filtersNode.isObject) refuse(QuestionRejection.malformed)
        return ParsedAnswer.Read(filters(filtersNode), unsupported, confidence)
    }

    private fun filters(node: JsonNode) = QuestionFilters(
        direction = optional(node.path("direction")) { dir ->
            dir.takeIf { it.isString }?.asString()?.let { name -> Direction.entries.firstOrNull { it.name == name } }
        },
        scopes = scopes(node.path("categories")),
        range = range(node.path("range")),
        isRefund = optionalBoolean(node.path("isRefund")),
        priorYear = optionalBoolean(node.path("priorYear")),
    )

    private fun scopes(node: JsonNode): List<ScopeRef> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) refuse(QuestionRejection.malformed)
        return node.values().map(::scope).distinct()
    }

    /** An object with a preset, or with from and/or to. Absent, null, or neither end: no period was named. */
    private fun range(node: JsonNode): QuestionRange? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isObject) refuse(QuestionRejection.malformed)
        val preset = node.path("preset")
        if (preset.isString) return QuestionRange.Preset(preset.asString())
        val from = optionalDate(node.path("from"))
        val to = optionalDate(node.path("to"))
        return if (from == null && to == null) null else QuestionRange.Between(from, to)
    }

    private fun scope(node: JsonNode): ScopeRef {
        val type = node.path("type").takeIf { it.isString }?.asString()
            ?.let { name -> ScopeType.entries.firstOrNull { it.name == name } }
        val code = node.path("code").takeIf { it.isString }?.asString()?.trim()?.takeIf { it.isNotEmpty() }
        if (type == null || code == null) refuse(QuestionRejection.malformed)
        return ScopeRef(type, code)
    }

    /** A string that is not a calendar date is a range problem, not a shape problem. */
    private fun optionalDate(node: JsonNode): LocalDate? = optional(node) {
        if (!it.isString) return@optional null
        try {
            LocalDate.parse(it.asString())
        } catch (_: DateTimeParseException) {
            refuse(QuestionRejection.invalid_range)
        }
    }

    private fun optionalBoolean(node: JsonNode): Boolean = optional(node) { it.takeIf { n -> n.isBoolean }?.asBoolean() } ?: false

    /** Absent or null is "not stated"; anything else must read as [read] says, or the answer is malformed. */
    private fun <T : Any> optional(node: JsonNode, read: (JsonNode) -> T?): T? {
        if (node.isMissingNode || node.isNull) return null
        return read(node) ?: refuse(QuestionRejection.malformed)
    }

    /** Lenient on purpose: an unknown kind is "other", a bare string is its own label. */
    private fun unsupported(node: JsonNode): List<UnsupportedFilter> {
        if (!node.isArray) return emptyList()
        return node.values().mapNotNull { entry ->
            val kind = entry.path("kind").takeIf { it.isString }?.asString()
                ?.let { name -> UnsupportedKind.entries.firstOrNull { it.name == name } }
                ?: UnsupportedKind.other
            val label = (if (entry.isString) entry else entry.path("label")).takeIf { it.isString }?.asString()?.trim()
            label?.takeIf { it.isNotEmpty() }?.let { UnsupportedFilter(kind, it.take(QuestionSchema.MAX_UNSUPPORTED_LABEL)) }
        }.take(QuestionSchema.MAX_UNSUPPORTED)
    }

    private fun refuse(reason: QuestionRejection): Nothing = throw Refused(reason)
}
