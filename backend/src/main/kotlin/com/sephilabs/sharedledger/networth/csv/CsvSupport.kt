package com.sephilabs.sharedledger.networth.csv

internal object CsvSupport {
    fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "1", "yes", "y", "si", "sí" -> true
        "false", "0", "no", "n", "" , null -> false
        else -> null
    }

    fun headerErrorArgs(expected: List<String>, actual: List<String>): String? {
        val missing = expected - actual.toSet()
        val unknown = actual - expected.toSet()
        if (missing.isEmpty() && unknown.isEmpty()) return null
        return (missing.map { "missing:$it" } + unknown.map { "unknown:$it" }).joinToString(", ")
    }
}
