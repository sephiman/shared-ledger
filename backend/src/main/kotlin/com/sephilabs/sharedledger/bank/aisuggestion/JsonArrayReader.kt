package com.sephilabs.sharedledger.bank.aisuggestion

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Finds the JSON array of objects in a model's answer: markdown fences, prose around the array and a cut-off
 *  array are all tolerated — every complete object before the cut still counts. */
object JsonArrayReader {

    private val mapper = JsonMapper.builder().build()

    /** The objects of the first JSON array in [raw] whose first element is an object, up to where it breaks;
     *  null when [raw] holds no such array at all. */
    fun objects(raw: String?): List<JsonNode>? {
        val text = raw?.replace(FENCE, "") ?: return null
        var start = text.indexOf('[')
        while (start >= 0) {
            val found = objectsFrom(text.substring(start))
            if (found != null) return found
            start = text.indexOf('[', start + 1)
        }
        return null
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
