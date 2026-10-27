package com.sephilabs.sharedledger.bank

/** The engine's text matching, shared so nothing outside it re-implements it: both sides trimmed and
 *  lowercased, `contains` a plain substring test with no word boundaries. */
fun textMatches(op: RuleOp, pattern: String, target: String?): Boolean {
    val value = target?.trim()?.lowercase() ?: return false
    val expected = pattern.trim().lowercase()
    return when (op) {
        RuleOp.equals -> value == expected
        RuleOp.contains -> value.contains(expected)
        RuleOp.range -> false
    }
}
