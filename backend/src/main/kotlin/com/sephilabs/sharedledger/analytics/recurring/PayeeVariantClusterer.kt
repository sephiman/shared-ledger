package com.sephilabs.sharedledger.analytics.recurring

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min

/** Clusters name variants of the same payee within a single category before recurring detection
 *  or merchant aggregation: merges case, spacing, punctuation, edit distance 1-2, and shared
 *  distinctive tokens while honouring user-recorded split rules. */
object PayeeVariantClusterer {

    private val NON_ALNUM = Regex("""[^a-z0-9\s]+""")
    private val WHITESPACE = Regex("""\s+""")

    // Generic commercial/descriptive tokens that must never merge two different payees on their own.
    private val GENERIC_TOKENS: Set<String> = setOf(
        "payment", "betaling", "bank", "banco", "direct", "online", "nederland", "netherlands",
        "europe", "group", "holding", "service", "services", "solutions", "systems", "store",
        "market", "super", "supermercado", "hotel", "parking", "gasolina", "fuel", "seguro",
        "seguros", "verzekering", "belasting", "taxes", "energy", "energie", "water", "mobile",
        "internet", "anual", "annual", "monthly", "mensual", "recibo", "factura", "invoice",
        "mantenimiento", "maintenance", "alquiler", "ticket", "tickets", "compra", "tarjeta",
        "envio", "abono", "cuota", "comision", "comisiones", "gastos", "ropa", "muebles",
        "farmacia", "clinica", "restaurante", "bakery", "national", "nationale", "general",
        "clientes", "klantenservice", "holland", "almere", "amsterdam", "madrid", "barcelona",
    )

    /** User-recorded split keeping [groups] of normalized variant keys permanently separate within [categoryCode]. */
    data class SplitRule(
        val categoryCode: String,
        val groups: List<Set<String>>,
    )

    data class PayeeCluster(
        val categoryCode: String,
        val canonicalMerchant: String,
        val canonicalKey: String,
        val anchorKey: String,
        val searchQuery: String,
        val mergedVariants: List<String>,
        val memberKeys: Set<String>,
        val records: List<ExpenseRecord>,
    )

    private data class KeyProfile(
        val key: String,
        val compact: String,
        val distinctiveTokens: Set<String>,
    )

    /** Normalizes a raw label into a punctuation-free, accent-folded, single-spaced lowercase key. */
    fun canonicalKey(label: String): String =
        NON_ALNUM.replace(RecurringDetectionConstants.foldAscii(label), " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** Stable cross-month key for a recurring or merchant subject using the lexicographically smallest variant key. */
    fun stableSubjectKey(kind: String, categoryCode: String, memberKeys: Set<String>): String {
        val anchor = memberKeys.filter { it.isNotBlank() }.minOrNull().orEmpty()
        return "$kind:$categoryCode:$anchor".take(250)
    }

    /** Stable unique key for a split decision over normalized variant partitions within [categoryCode]. */
    fun splitSubjectKey(categoryCode: String, groups: List<Set<String>>): String {
        val canonical = groups
            .map { g -> g.map(::canonicalKey).filter { it.isNotBlank() }.sorted().joinToString(",") }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString("|")
        val raw = "split:$categoryCode:$canonical"
        if (raw.length <= 240) return raw
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return "split:$categoryCode:$digest"
    }

    fun areVariants(labelA: String, labelB: String): Boolean =
        areProfileVariants(profile(canonicalKey(labelA)), profile(canonicalKey(labelB)))

    fun clusterByCategory(
        records: List<ExpenseRecord>,
        splitRules: List<SplitRule> = emptyList(),
    ): List<PayeeCluster> {
        val labeled = records.filter { !it.rawLabel.isNullOrBlank() }
        val byCategory = labeled.groupBy { it.categoryCode }
        val rulesByCategory = splitRules.groupBy { it.categoryCode }
        val clusters = mutableListOf<PayeeCluster>()

        for ((categoryCode, catRecords) in byCategory) {
            val byKey = catRecords.groupBy { canonicalKey(it.rawLabel!!) }
                .filterKeys { it.isNotEmpty() }
            val keys = byKey.keys.sorted()
            if (keys.isEmpty()) continue

            val catRules = rulesByCategory[categoryCode].orEmpty()
                .map { rule -> rule.groups.map { g -> g.map(::canonicalKey).filter { it.isNotBlank() }.toSet() }.filter { it.isNotEmpty() } }
                .filter { it.size >= 2 }

            val profiles = keys.map(::profile)
            val keyToIdx = keys.withIndex().associate { (idx, k) -> k to idx }

            // Track which (ruleIdx, groupIdx) each declared key belongs to.
            val declaredSides = Array(keys.size) { mutableSetOf<Pair<Int, Int>>() }
            for ((rIdx, groups) in catRules.withIndex()) {
                for ((gIdx, group) in groups.withIndex()) {
                    for (member in group) {
                        keyToIdx[member]?.let { idx -> declaredSides[idx] += rIdx to gIdx }
                    }
                }
            }

            // Pre-build profiles for every rule group (including keys absent from the current window) so a new
            // variant introduced in a later month is classified strictly against all split sides.
            val ruleGroupProfiles: List<List<List<KeyProfile>>> = catRules.map { groups ->
                groups.map { group -> group.map(::profile) }
            }

            // For any undeclared (new) key, check whether it matches multiple groups of any split rule.
            // A key matching >=2 sides is a bridge and stays unattached to that rule rather than re-merging it;
            // a key matching exactly 1 side attaches to that side only.
            val bridgeRulesForIdx = Array(keys.size) { mutableSetOf<Int>() }
            val attachedSides = Array(keys.size) { declaredSides[it].toMutableSet() }
            for (idx in keys.indices) {
                for ((rIdx, groupProfileLists) in ruleGroupProfiles.withIndex()) {
                    if (declaredSides[idx].any { it.first == rIdx }) continue
                    val matchedGroups = mutableListOf<Int>()
                    for ((gIdx, gProfiles) in groupProfileLists.withIndex()) {
                        if (gProfiles.any { gp -> areProfileVariants(profiles[idx], gp) }) {
                            matchedGroups += gIdx
                        }
                    }
                    when (matchedGroups.size) {
                        1 -> attachedSides[idx] += rIdx to matchedGroups[0]
                        in 2..Int.MAX_VALUE -> bridgeRulesForIdx[idx] += rIdx
                    }
                }
            }

            val parent = IntArray(keys.size) { it }
            val compSides = Array(keys.size) { attachedSides[it].toMutableSet() }
            val compBridgeRules = Array(keys.size) { bridgeRulesForIdx[it].toMutableSet() }

            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) {
                    parent[r] = parent[parent[r]]
                    r = parent[r]
                }
                return r
            }

            fun canUnion(ra: Int, rb: Int): Boolean {
                if (ra == rb) return true
                // Never merge two components that contain different groups of the same split rule.
                for ((rIdx, gA) in compSides[ra]) {
                    if (compSides[rb].any { it.first == rIdx && it.second != gA }) return false
                    if (rIdx in compBridgeRules[rb]) return false
                }
                for ((rIdx, _) in compSides[rb]) {
                    if (rIdx in compBridgeRules[ra]) return false
                }
                return true
            }

            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra == rb || !canUnion(ra, rb)) return
                parent[rb] = ra
                compSides[ra] += compSides[rb]
                compBridgeRules[ra] += compBridgeRules[rb]
            }

            // 1. Keep keys explicitly placed in the same split group together.
            for ((rIdx, groups) in catRules.withIndex()) {
                for (group in groups) {
                    val present = group.mapNotNull { keyToIdx[it] }
                    for (k in 1 until present.size) {
                        union(present[0], present[k])
                    }
                }
                // Attach unambiguous new variants that matched exactly one side of this split rule.
                for (idx in keys.indices) {
                    if (declaredSides[idx].any { it.first == rIdx }) continue
                    val targetSide = attachedSides[idx].firstOrNull { it.first == rIdx } ?: continue
                    val anchorIdx = groups[targetSide.second].firstNotNullOfOrNull { keyToIdx[it] } ?: continue
                    union(anchorIdx, idx)
                }
            }

            // 2. Standard variant merging for all non-conflicting pairs.
            for (i in profiles.indices) {
                for (j in i + 1 until profiles.size) {
                    if (areProfileVariants(profiles[i], profiles[j])) {
                        union(i, j)
                    }
                }
            }

            val groupedIndices = keys.indices.groupBy { find(it) }
            for (idxs in groupedIndices.values) {
                val memberKeys = idxs.map { keys[it] }.toSet()
                val mergedRecords = idxs.flatMap { byKey[keys[it]].orEmpty() }.sortedBy { it.occurrenceDate }
                val canonicalMerchant = pickCanonicalLabel(mergedRecords)
                val searchQuery = pickSearchQuery(mergedRecords, canonicalMerchant)
                val mergedVariants = collectDistinctVariants(mergedRecords)
                val anchorKey = memberKeys.minOrNull() ?: canonicalKey(canonicalMerchant)
                clusters += PayeeCluster(
                    categoryCode = categoryCode,
                    canonicalMerchant = canonicalMerchant,
                    canonicalKey = canonicalKey(canonicalMerchant),
                    anchorKey = anchorKey,
                    searchQuery = searchQuery,
                    mergedVariants = mergedVariants,
                    memberKeys = memberKeys,
                    records = mergedRecords,
                )
            }
        }
        return clusters
    }

    private fun profile(key: String): KeyProfile {
        val compact = key.replace(" ", "")
        val tokens = key.split(' ')
            .filter { it.length >= RecurringDetectionConstants.PAYEE_VARIANT_DISTINCTIVE_TOKEN_MIN_LEN && it !in GENERIC_TOKENS }
            .toSet()
        return KeyProfile(key, compact, tokens)
    }

    private fun areProfileVariants(a: KeyProfile, b: KeyProfile): Boolean {
        if (a.compact == b.compact) return true

        // Shared distinctive token (e.g. "Nat Postcode", "Nat postcode loterij", "Postcode Loterij").
        if (a.distinctiveTokens.isNotEmpty() && a.distinctiveTokens.any { it in b.distinctiveTokens }) {
            return true
        }

        // Typo/edit-distance merge (1-2 edits on compact form of sufficient length, never merging numeric suffixes).
        val minLen = min(a.compact.length, b.compact.length)
        val lenDiff = abs(a.compact.length - b.compact.length)
        val maxDist = when {
            minLen >= RecurringDetectionConstants.PAYEE_VARIANT_LONG_KEY_MIN_LEN -> RecurringDetectionConstants.PAYEE_VARIANT_MAX_EDIT_DIST_LONG
            minLen >= RecurringDetectionConstants.PAYEE_VARIANT_SHORT_KEY_MIN_LEN -> RecurringDetectionConstants.PAYEE_VARIANT_MAX_EDIT_DIST_SHORT
            else -> return false
        }
        if (lenDiff > maxDist) return false
        // Require the same initial character so unrelated short names ("apple" vs "bpple") or trailing numbers don't collapse.
        if (a.compact.first() != b.compact.first()) return false
        if (a.compact.any { it.isDigit() } || b.compact.any { it.isDigit() }) {
            val digitsA = a.compact.filter { it.isDigit() }
            val digitsB = b.compact.filter { it.isDigit() }
            if (digitsA != digitsB) return false
        }
        return levenshtein(a.compact, b.compact, maxDist) <= maxDist
    }

    /** Returns the distinct merged variant names (ordered by frequency desc, case-insensitive dedup)
     *  when more than one variant was combined; empty list when the cluster came from a single label. */
    private fun collectDistinctVariants(records: List<ExpenseRecord>): List<String> {
        val byLower = records
            .mapNotNull { r -> r.rawLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { it to r.occurrenceDate } }
            .groupBy { it.first.lowercase() }
        if (byLower.size <= 1) return emptyList()
        return byLower.values
            .sortedWith(
                compareByDescending<List<Pair<String, java.time.LocalDate>>> { it.size }
                    .thenBy { it.first().first.lowercase() },
            )
            .map { group ->
                group.maxWith(
                    compareBy<Pair<String, java.time.LocalDate>> { pair -> pair.first.count { it.isUpperCase() } }
                        .thenBy { it.second },
                ).first
            }
    }

    private fun pickCanonicalLabel(records: List<ExpenseRecord>): String {
        val byLower = records.mapNotNull { r -> r.rawLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { it to r.occurrenceDate } }
            .groupBy { it.first.lowercase() }
        val winningGroup = byLower.values.maxWith(
            compareBy<List<Pair<String, java.time.LocalDate>>> { it.size }
                .thenBy { group -> group.maxOf { it.second } },
        )
        return winningGroup.maxBy { it.second }.first
    }

    /** Picks a query string for `/transactions?q=...` that matches all merged variants when a common
     *  distinctive token exists (e.g. "Postcode"), otherwise falls back to the canonical merchant label. */
    private fun pickSearchQuery(records: List<ExpenseRecord>, canonicalMerchant: String): String {
        val distinctRaw = records.mapNotNull { it.rawLabel?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinctBy { it.lowercase() }
        if (distinctRaw.size <= 1) return canonicalMerchant

        val foldedAll = distinctRaw.map { RecurringDetectionConstants.foldAscii(it) }
        val candidateTokens = canonicalMerchant.split(Regex("""[\s\-_./]+"""))
            .map { it.trim() }
            .filter {
                val folded = RecurringDetectionConstants.foldAscii(it)
                folded.length >= RecurringDetectionConstants.PAYEE_VARIANT_DISTINCTIVE_TOKEN_MIN_LEN && folded !in GENERIC_TOKENS
            }
            .sortedByDescending { it.length }

        for (tok in candidateTokens) {
            val foldedTok = RecurringDetectionConstants.foldAscii(tok)
            if (foldedAll.all { it.contains(foldedTok) }) {
                val capitalized = distinctRaw.flatMap { it.split(Regex("""[\s\-_./]+""")) }
                    .firstOrNull { RecurringDetectionConstants.foldAscii(it) == foldedTok && it.firstOrNull()?.isUpperCase() == true }
                return capitalized ?: tok
            }
        }
        return canonicalMerchant
    }

    private fun levenshtein(a: String, b: String, maxAllowed: Int): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > maxAllowed) return maxAllowed + 1
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxAllowed) return maxAllowed + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }
}
