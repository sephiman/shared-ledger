package com.sephilabs.sharedledger.ai

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/** Shared compact JSON helpers for AI payload builders and response sanitizers. */
object AiJsonUtils {

    private val mapper = JsonMapper.builder().build()

    /** Deterministic compact JSON with lexicographically sorted object keys so payload bytes remain
     *  byte-for-byte identical before and after a PostgreSQL JSONB round-trip. */
    fun canonicalJson(node: JsonNode): String = mapper.writeValueAsString(sortKeys(node))

    private fun sortKeys(node: JsonNode): JsonNode = when {
        node.isObject -> {
            val sorted = mapper.createObjectNode()
            node.properties().asSequence().sortedBy { it.key }.forEach { (k, v) ->
                sorted.set(k, sortKeys(v))
            }
            sorted
        }
        node.isArray -> {
            val arr: ArrayNode = mapper.createArrayNode()
            node.forEach { arr.add(sortKeys(it)) }
            arr
        }
        else -> node
    }

    fun ObjectNode.putNumber(field: String, value: BigDecimal) {
        put(field, value.toDouble())
    }

    fun ObjectNode.putNullableNumber(field: String, value: BigDecimal?) {
        if (value == null) putNull(field) else put(field, value.toDouble())
    }

    fun ObjectNode.putNullableDouble(field: String, value: Double?) {
        if (value == null || !value.isFinite()) putNull(field) else put(field, value)
    }

    fun ObjectNode.putNullableInt(field: String, value: Int?) {
        if (value == null) putNull(field) else put(field, value)
    }

    fun decimalField(node: JsonNode): BigDecimal? = when {
        node.isNumber -> node.decimalValue()
        node.isString -> node.asString().trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        else -> null
    }

    fun intField(node: JsonNode): Int? = when {
        node.isInt || node.isLong -> node.asInt()
        node.isNumber -> node.doubleValue().takeIf { it == it.toInt().toDouble() }?.toInt()
        node.isString -> node.asString().trim().toIntOrNull()
        else -> null
    }
}
