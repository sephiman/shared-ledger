package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.aisuggestion.JsonArrayReader
import com.sephilabs.sharedledger.transaction.Direction
import tools.jackson.databind.JsonNode

sealed interface ParsedProposal {
    data class Valid(
        val pattern: String,
        val field: RuleField,
        val direction: Direction,
        val categoryCode: String,
        val absorbs: List<String>,
        val confidence: Double,
    ) : ParsedProposal

    data class Invalid(val reason: ProposalParseFailure) : ParsedProposal
}

enum class ProposalParseFailure { malformed, unknown_rule, single_rule }

/** [readable] is false when the answer held no array at all — a failed run, not an empty proposal list. */
data class ParsedAnswer(val readable: Boolean, val proposals: List<ParsedProposal>)

/** Shape only: whether a pattern actually fits its rules is [RuleGeneralisationValidator]'s job. */
object RuleGeneralisationParser {

    /** [knownIds] are the prompt ids (`r1…rN`) that were sent. */
    fun parse(raw: String?, knownIds: Set<String>): ParsedAnswer {
        val objects = JsonArrayReader.objects(raw) ?: return ParsedAnswer(readable = false, proposals = emptyList())
        return ParsedAnswer(readable = true, proposals = objects.map { judge(it, knownIds) })
    }

    private fun judge(entry: JsonNode, knownIds: Set<String>): ParsedProposal {
        val pattern = entry.path("pattern").takeIf { it.isString }?.asString()?.trim()?.takeIf { it.isNotEmpty() }
        val field = entry.path("field").takeIf { it.isString }?.asString()
            ?.let { name -> GENERALISABLE_FIELDS.firstOrNull { it.name == name } }
        val direction = entry.path("direction").takeIf { it.isString }?.asString()
            ?.let { name -> Direction.entries.firstOrNull { it.name == name } }
        val categoryCode = entry.path("categoryCode").takeIf { it.isString }?.asString()
        val absorbs = absorbs(entry.path("absorbs"))
        val confidence = entry.path("confidence").takeIf { it.isNumber }?.asDouble()?.takeIf { it in 0.0..1.0 }
        if (pattern == null || field == null || direction == null || categoryCode == null || absorbs == null || confidence == null) {
            return ParsedProposal.Invalid(ProposalParseFailure.malformed)
        }
        if (absorbs.any { it !in knownIds }) return ParsedProposal.Invalid(ProposalParseFailure.unknown_rule)
        if (absorbs.size < 2) return ParsedProposal.Invalid(ProposalParseFailure.single_rule)
        return ParsedProposal.Valid(pattern, field, direction, categoryCode, absorbs, confidence)
    }

    /** Distinct ids in answer order; null unless an array of strings. */
    private fun absorbs(node: JsonNode): List<String>? {
        if (!node.isArray || node.values().any { !it.isString }) return null
        return node.values().map { it.asString() }.distinct()
    }
}
