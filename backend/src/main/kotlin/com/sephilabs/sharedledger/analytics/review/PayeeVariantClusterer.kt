package com.sephilabs.sharedledger.analytics.review

import kotlin.math.abs
import kotlin.math.min

/** Clusters name variants of the same payee within a single category before recurring detection
 *  or merchant aggregation: merges case, spacing, punctuation, edit distance 1-2, and shared
 *  distinctive tokens without collapsing unrelated payees. */
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

    data class PayeeCluster(
        val categoryCode: String,
        val canonicalMerchant: String,
        val canonicalKey: String,
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
        NON_ALNUM.replace(MerchantSanitizer.fold(label), " ")
            .replace(WHITESPACE, " ")
            .trim()

    fun areVariants(labelA: String, labelB: String): Boolean =
        areProfileVariants(profile(canonicalKey(labelA)), profile(canonicalKey(labelB)))

    fun clusterByCategory(records: List<ExpenseRecord>): List<PayeeCluster> {
        val labeled = records.filter { !it.rawLabel.isNullOrBlank() }
        val byCategory = labeled.groupBy { it.categoryCode }
        val clusters = mutableListOf<PayeeCluster>()

        for ((categoryCode, catRecords) in byCategory) {
            val byKey = catRecords.groupBy { canonicalKey(it.rawLabel!!) }
                .filterKeys { it.isNotEmpty() }
            val keys = byKey.keys.toList()
            if (keys.isEmpty()) continue

            val profiles = keys.map(::profile)
            val parent = IntArray(keys.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) {
                    parent[r] = parent[parent[r]]
                    r = parent[r]
                }
                return r
            }
            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[rb] = ra
            }

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
                clusters += PayeeCluster(
                    categoryCode = categoryCode,
                    canonicalMerchant = canonicalMerchant,
                    canonicalKey = canonicalKey(canonicalMerchant),
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
            .filter { it.length >= SpendingReviewConstants.PAYEE_VARIANT_DISTINCTIVE_TOKEN_MIN_LEN && it !in GENERIC_TOKENS }
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
            minLen >= SpendingReviewConstants.PAYEE_VARIANT_LONG_KEY_MIN_LEN -> SpendingReviewConstants.PAYEE_VARIANT_MAX_EDIT_DIST_LONG
            minLen >= SpendingReviewConstants.PAYEE_VARIANT_SHORT_KEY_MIN_LEN -> SpendingReviewConstants.PAYEE_VARIANT_MAX_EDIT_DIST_SHORT
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
                // Prefer the most capitalized casing (e.g. "Nat Postcode Loterij" over "Nat postcode loterij"),
                // breaking ties by latest occurrence date.
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
        // Prefer the most recent casing of the most frequent label.
        return winningGroup.maxBy { it.second }.first
    }

    /** Picks a query string for `/transactions?q=...` that matches all merged variants when a common
     *  distinctive token exists (e.g. "Postcode"), otherwise falls back to the canonical merchant label. */
    private fun pickSearchQuery(records: List<ExpenseRecord>, canonicalMerchant: String): String {
        val distinctRaw = records.mapNotNull { it.rawLabel?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinctBy { it.lowercase() }
        if (distinctRaw.size <= 1) return canonicalMerchant

        val foldedAll = distinctRaw.map { MerchantSanitizer.fold(it) }
        val candidateTokens = canonicalMerchant.split(Regex("""[\s\-_./]+"""))
            .map { it.trim() }
            .filter {
                val folded = MerchantSanitizer.fold(it)
                folded.length >= SpendingReviewConstants.PAYEE_VARIANT_DISTINCTIVE_TOKEN_MIN_LEN && folded !in GENERIC_TOKENS
            }
            .sortedByDescending { it.length }

        for (tok in candidateTokens) {
            val foldedTok = MerchantSanitizer.fold(tok)
            if (foldedAll.all { it.contains(foldedTok) }) {
                val capitalized = distinctRaw.flatMap { it.split(Regex("""[\s\-_./]+""")) }
                    .firstOrNull { MerchantSanitizer.fold(it) == foldedTok && it.firstOrNull()?.isUpperCase() == true }
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
