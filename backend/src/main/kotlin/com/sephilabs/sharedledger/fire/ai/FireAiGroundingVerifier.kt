package com.sephilabs.sharedledger.fire.ai

import tools.jackson.databind.JsonNode
import kotlin.math.abs

/**
 * Verifies that every cited {path, value} in a FIRE plan review or follow-up exists in the
 * pre-computed payload with a matching value (within small rounding tolerance for numbers).
 */
object FireAiGroundingVerifier {

    data class CitationCheck(
        val path: String,
        val grounded: Boolean,
        val citedValue: JsonNode?,
        val actualValue: JsonNode?,
    )

    data class Result(val total: Int, val ungrounded: Int, val checks: List<CitationCheck>)

    fun verify(payload: JsonNode, answer: JsonNode): Result {
        val checks = mutableListOf<CitationCheck>()
        for (section in listOf("strengths", "improvements", "suggestedWhatIfs", "observations", "dataGaps")) {
            answer.path(section).forEach { item ->
                item.path("citations").forEach { checks += check(payload, it) }
            }
        }
        return Result(checks.size, checks.count { !it.grounded }, checks)
    }

    fun verifyCitations(payload: JsonNode, citations: JsonNode): Result {
        val checks = if (citations.isArray) citations.toList().map { check(payload, it) } else emptyList()
        return Result(checks.size, checks.count { !it.grounded }, checks)
    }

    fun check(payload: JsonNode, citation: JsonNode): CitationCheck {
        val path = citation.path("path").takeIf { it.isString }?.asString()
        val cited = citation.path("value").takeIf { !it.isMissingNode }
        if (path.isNullOrBlank() || cited == null) {
            return CitationCheck(path ?: "", grounded = false, citedValue = cited, actualValue = null)
        }
        val actual = resolve(payload, path)
        return CitationCheck(path, matches(cited, actual), cited, actual)
    }

    fun resolve(payload: JsonNode, path: String): JsonNode? {
        var node: JsonNode = payload
        for (rawSegment in path.replace("[", ".").replace("]", "").split('.')) {
            if (rawSegment.isEmpty()) continue
            node = if (node.isArray && rawSegment.toIntOrNull() != null) {
                node.path(rawSegment.toInt())
            } else {
                node.path(rawSegment)
            }
            if (node.isMissingNode) return null
        }
        return node
    }

    private fun matches(cited: JsonNode, actual: JsonNode?): Boolean {
        if (actual == null) return false
        return when {
            cited.isNull && actual.isNull -> true
            cited.isNumber && actual.isNumber ->
                abs(cited.doubleValue() - actual.doubleValue()) <= tolerance(actual.doubleValue())
            // Numeric fields formatted as strings (e.g. "2000.00") may be cited as numbers or strings.
            cited.isNumber && actual.isString -> {
                val actualNum = actual.asString().toDoubleOrNull() ?: return false
                abs(cited.doubleValue() - actualNum) <= tolerance(actualNum)
            }
            cited.isString && actual.isNumber -> {
                val citedNum = cited.asString().toDoubleOrNull() ?: return false
                abs(citedNum - actual.doubleValue()) <= tolerance(actual.doubleValue())
            }
            cited.isBoolean && actual.isBoolean -> cited.booleanValue() == actual.booleanValue()
            cited.isString && actual.isString -> cited.asString().trim() == actual.asString().trim()
            cited.isArray && actual.isArray ->
                cited.size() == actual.size() && (0 until cited.size()).all { matches(cited[it], actual[it]) }
            cited.isObject && actual.isObject -> {
                val citedKeys = cited.properties().asSequence().map { it.key }.toSet()
                val actualKeys = actual.properties().asSequence().map { it.key }.toSet()
                citedKeys == actualKeys && citedKeys.all { matches(cited.path(it), actual.path(it)) }
            }
            else -> false
        }
    }

    private fun tolerance(actual: Double): Double = maxOf(0.011, abs(actual) * 0.0005)
}
