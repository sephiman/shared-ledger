package com.sephilabs.sharedledger.catalog

/**
 * Legacy asset-class codes accepted on CSV import and mapped to their current names,
 * so files exported before a rename keep importing. 'index_funds' became 'fund' in V013.
 */
object AssetClassAliases {
    private val LEGACY_TO_CURRENT = mapOf("index_funds" to "fund")

    fun canonical(code: String): String = LEGACY_TO_CURRENT[code] ?: code
}
