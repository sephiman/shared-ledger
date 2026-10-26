package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.transaction.Direction
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

sealed interface ParsedSuggestion {
    data class Valid(val categoryCode: String, val confidence: Double) : ParsedSuggestion
    data class Invalid(val reason: InvalidReason) : ParsedSuggestion
}

enum class InvalidReason { missing, unknown_code, direction_mismatch, invalid_confidence }

/** Reads the model's answer defensively: markdown fences, prose around the array and a cut-off array are all
 *  tolerated — every complete entry before the cut still counts. Anything that is not a catalogue code of the
 *  movement's own direction with a confidence in [0, 1] is [ParsedSuggestion.Invalid]. */
object CategorySuggestionParser {

    private val mapper = JsonMapper.builder().build()

    /** One entry per id of [batch] (id -> the movement's direction); [catalogue] is code -> direction. */
    fun parse(
        raw: String?,
        batch: Map<String, Direction>,
        catalogue: Map<String, Direction>,
    ): Map<String, ParsedSuggestion> {
        val result = batch.keys.associateWith<String, ParsedSuggestion> { ParsedSuggestion.Invalid(InvalidReason.missing) }
            .toMutableMap()
        val seen = mutableSetOf<String>()
        for (entry in entries(raw)) {
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

    /** The objects of the first JSON array in [raw] whose first element is an object, up to where it breaks. */
    private fun entries(raw: String?): List<JsonNode> {
        val text = raw?.replace(FENCE, "") ?: return emptyList()
        var start = text.indexOf('[')
        while (start >= 0) {
            val found = objectsFrom(text.substring(start))
            if (found != null) return found
            start = text.indexOf('[', start + 1)
        }
        return emptyList()
    }

    /** null when the array at the start of [text] is not an array of objects (e.g. "[note]" in prose). */
    private fun objectsFrom(text: String): List<JsonNode>? {
        val objects = mutableListOf<JsonNode>()
        try {
            mapper.createParser(text).use { parser ->
                if (parser.nextToken() != JsonToken.START_ARRAY) return null
                while (true) {
                    when (parser.nextToken()) {
                        JsonToken.START_OBJECT -> objects.add(parser.readValueAsTree<JsonNode>())
                        JsonToken.END_ARRAY, null -> break
                        else -> if (objects.isEmpty()) return null else parser.skipChildren()
                    }
                }
            }
        } catch (_: JacksonException) {
            // A truncated answer: keep what parsed completely.
            if (objects.isEmpty()) return null
        }
        return objects
    }

    private val FENCE = Regex("```(?:json)?")
}
