package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.transaction.Direction
import tools.jackson.databind.JsonNode

sealed interface ParsedSuggestion {
    data class Valid(val categoryCode: String, val confidence: Double) : ParsedSuggestion
    data class Invalid(val reason: InvalidReason) : ParsedSuggestion
}

enum class InvalidReason { missing, unknown_code, direction_mismatch, invalid_confidence }

/** Reads the model's answer defensively: markdown fences, prose around the array and a cut-off array are all
 *  tolerated (see [JsonArrayReader]). Anything that is not a catalogue code of the
 *  movement's own direction with a confidence in [0, 1] is [ParsedSuggestion.Invalid]. */
object CategorySuggestionParser {

    /** One entry per id of [batch] (id -> the movement's direction); [catalogue] is code -> direction. */
    fun parse(
        raw: String?,
        batch: Map<String, Direction>,
        catalogue: Map<String, Direction>,
    ): Map<String, ParsedSuggestion> {
        val result = batch.keys.associateWith<String, ParsedSuggestion> { ParsedSuggestion.Invalid(InvalidReason.missing) }
            .toMutableMap()
        val seen = mutableSetOf<String>()
        for (entry in JsonArrayReader.objects(raw).orEmpty()) {
            val id = entry.path("id").takeIf { it.isString }?.asString() ?: continue
            val direction = batch[id] ?: continue
            if (!seen.add(id)) continue
            result[id] = judge(entry, direction, catalogue)
        }
        return result
    }

    private fun judge(entry: JsonNode, direction: Direction, catalogue: Map<String, Direction>): ParsedSuggestion {
        val code = entry.path("categoryCode").takeIf { it.isString }?.asString()
        val categoryDirection = code?.let { catalogue[it] } ?: return ParsedSuggestion.Invalid(InvalidReason.unknown_code)
        if (categoryDirection != direction) return ParsedSuggestion.Invalid(InvalidReason.direction_mismatch)
        val confidence = entry.path("confidence").takeIf { it.isNumber }?.asDouble()
        if (confidence == null || confidence !in 0.0..1.0) return ParsedSuggestion.Invalid(InvalidReason.invalid_confidence)
        return ParsedSuggestion.Valid(code, confidence)
    }
}
