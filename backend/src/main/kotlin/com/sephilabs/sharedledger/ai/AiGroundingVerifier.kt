package com.sephilabs.sharedledger.ai

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Verifies that every cited {path, value} in an AI review or follow-up exists in the
 * pre-computed payload with a matching value (within small rounding tolerance for numbers).
 */
object AiGroundingVerifier {

    data class CitationCheck(
        val path: String,
        val grounded: Boolean,
        val citedValue: JsonNode?,
        val actualValue: JsonNode?,
    )

    data class Result(val total: Int, val ungrounded: Int, val checks: List<CitationCheck>)

    fun verifySections(payload: JsonNode, answer: JsonNode, sections: Collection<String>): Result {
        val checks = mutableListOf<CitationCheck>()
        for (section in sections) {
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
        val figureKind = if (path.endsWith(".value")) {
            resolve(payload, path.removeSuffix(".value"))?.path("kind")?.takeIf { it.isString }?.asString()
        } else null
        return CitationCheck(path, matches(cited, actual, path, figureKind), cited, actual)
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

    private fun matches(cited: JsonNode, actual: JsonNode?, path: String, figureKind: String? = null): Boolean {
        if (actual == null) return false
        return when {
            cited.isNull && actual.isNull -> true
            cited.isNumber && actual.isNumber ->
                matchesNumber(cited.doubleValue(), actual.doubleValue(), path, figureKind)
            // Numeric fields formatted as strings (e.g. "2000.00") may be cited as numbers or strings.
            cited.isNumber && actual.isString -> {
                val actualNum = actual.asString().toDoubleOrNull() ?: return false
                matchesNumber(cited.doubleValue(), actualNum, path, figureKind)
            }
            cited.isString && actual.isNumber -> {
                val citedNum = cited.asString().toDoubleOrNull() ?: return false
                matchesNumber(citedNum, actual.doubleValue(), path, figureKind)
            }
            cited.isBoolean && actual.isBoolean -> cited.booleanValue() == actual.booleanValue()
            cited.isString && actual.isString -> cited.asString().trim() == actual.asString().trim()
            cited.isArray && actual.isArray ->
                cited.size() == actual.size() && (0 until cited.size()).all { matches(cited[it], actual[it], "$path.$it") }
            cited.isObject && actual.isObject -> {
                val citedKeys = cited.properties().asSequence().map { it.key }.toSet()
                val actualKeys = actual.properties().asSequence().map { it.key }.toSet()
                citedKeys == actualKeys && citedKeys.all { matches(cited.path(it), actual.path(it), "$path.$it") }
            }
            else -> false
        }
    }

    /** Exact match within floating-point/2-decimal epsilon OR exact rounding (HALF_EVEN / HALF_UP)
     *  to the UI's displayed precision: 1 decimal for percentage leaves (Pct, Percent, Share) and whole
     *  units for non-count money values >= 1. Never loosens beyond displayed-precision rounding. */
    private fun matchesNumber(cited: Double, actual: Double, path: String, figureKind: String? = null): Boolean {
        if (!cited.isFinite() || !actual.isFinite()) return false
        if (abs(cited - actual) <= tolerance(actual)) return true
        val leaf = path.substringAfterLast('.').substringBefore('[')
        if (leaf in EXACT_INTEGER_LEAVES || figureKind == "count") return false
        val actualDec = BigDecimal.valueOf(actual)
        val isPct = leaf.endsWith("Pct") || leaf.endsWith("Percent") || leaf.endsWith("Share") ||
            figureKind == "percent" || figureKind == "multiple"
        if (isPct) {
            val citedScaled10 = cited * 10.0
            if (abs(citedScaled10 - kotlin.math.round(citedScaled10)) > 1e-6) return false
            val halfEven = actualDec.setScale(1, RoundingMode.HALF_EVEN).toDouble()
            val halfUp = actualDec.setScale(1, RoundingMode.HALF_UP).toDouble()
            return abs(cited - halfEven) <= 1e-6 || abs(cited - halfUp) <= 1e-6
        }
        // Whole-unit rounding (e.g. whole euros in reports): cited must be an integer and |actual| >= 1.
        if (abs(actual) >= 1.0 && cited == floor(cited)) {
            val halfEven = actualDec.setScale(0, RoundingMode.HALF_EVEN).toDouble()
            val halfUp = actualDec.setScale(0, RoundingMode.HALF_UP).toDouble()
            return abs(cited - halfEven) <= 1e-6 || abs(cited - halfUp) <= 1e-6
        }
        return false
    }

    private val EXACT_INTEGER_LEAVES = setOf(
        "occurrences", "purchases", "nonZeroMonths", "monthsAvailable",
        "daysElapsed", "daysInMonth", "daysRemaining", "merchantCount",
        "budgetedCategoriesCount", "activeExpenseCategoriesCount",
        "periodYear", "periodMonth", "priorYear", "targetMonthly",
        "startYear", "targetYear", "estimatedYear", "deterministicHitYear",
        "medianYearAmongReaching", "coastEstimatedYear", "drawdownYears",
        "monteCarloTrials", "horizonMonths", "windowMonths",
    )

    private fun tolerance(actual: Double): Double = maxOf(0.011, abs(actual) * 0.0005)
}
