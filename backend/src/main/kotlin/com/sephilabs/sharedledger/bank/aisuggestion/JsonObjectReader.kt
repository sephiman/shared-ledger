package com.sephilabs.sharedledger.bank.aisuggestion

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** [JsonArrayReader]'s single-object sibling: markdown fences and prose around the object are tolerated, but a
 *  cut-off object is not — half an answer is no answer. */
object JsonObjectReader {

    private val mapper = JsonMapper.builder().build()

    /** The first complete JSON object in [raw], or null when there is none. */
    fun first(raw: String?): JsonNode? {
        val text = raw?.replace(FENCE, "") ?: return null
        var start = text.indexOf('{')
        while (start >= 0) {
            objectAt(text.substring(start))?.let { return it }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun objectAt(text: String): JsonNode? = try {
        mapper.createParser(text).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) null else parser.readValueAsTree<JsonNode>()
        }
    } catch (_: JacksonException) {
        null
    }

    private val FENCE = Regex("```(?:json)?")
}
