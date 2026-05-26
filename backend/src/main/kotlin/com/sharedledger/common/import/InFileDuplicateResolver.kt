package com.sharedledger.common.import

object InFileDuplicateResolver {
    private const val MAX_DESCRIPTION_LENGTH = 500

    data class ResolutionResult(
        val adjustedRows: List<AdjustedRow>,
        val adjustedCount: Int,
    )

    /**
     * Walks [rows] in order and rewrites the description of any row that duplicates an
     * earlier-in-file row, appending (2), (3), … until the key is unique against both the
     * in-file rows already processed and [existingKeys] (rows already in the DB).
     *
     * Rows whose original key already exists in [existingKeys] are left untouched — they
     * will be DB-skipped at execute time. Disambiguating them would silently insert a
     * phantom row on every re-import of the same CSV.
     */
    fun <T> resolveAll(
        rows: MutableList<T>,
        existingKeys: Set<String>,
        keyOf: (T, String?) -> String,
        descriptionOf: (T) -> String?,
        rowNumberOf: (T) -> Int,
        withDescription: (T, String) -> T,
        summarize: (T) -> String,
        maxReported: Int = MAX_SKIPPED_REPORTED,
    ): ResolutionResult {
        val seenKeys = mutableSetOf<String>()
        val adjustedRows = mutableListOf<AdjustedRow>()
        var adjustedCount = 0
        for (i in rows.indices) {
            val row = rows[i]
            val rowKeyOf: (String?) -> String = { desc -> keyOf(row, desc) }
            val initialKey = rowKeyOf(descriptionOf(row))
            if (initialKey in existingKeys) {
                seenKeys.add(initialKey)
                continue
            }
            val newDesc = disambiguate(
                initialDescription = descriptionOf(row),
                seenKeys = seenKeys,
                existingKeys = existingKeys,
                keyOf = rowKeyOf,
            )
            if (newDesc != null) {
                val originalSummary = summarize(row)
                rows[i] = withDescription(row, newDesc)
                adjustedCount++
                if (adjustedRows.size < maxReported) {
                    adjustedRows.add(AdjustedRow(rowNumberOf(row), originalSummary, newDesc))
                }
            }
            seenKeys.add(rowKeyOf(descriptionOf(rows[i])))
        }
        return ResolutionResult(adjustedRows, adjustedCount)
    }

    fun disambiguate(
        initialDescription: String?,
        seenKeys: Set<String>,
        existingKeys: Set<String>,
        keyOf: (String?) -> String,
    ): String? {
        if (keyOf(initialDescription) !in seenKeys) return null
        var n = 2
        while (true) {
            val candidate = buildCandidate(initialDescription, n)
            val candidateKey = keyOf(candidate)
            if (candidateKey !in seenKeys && candidateKey !in existingKeys) return candidate
            n++
        }
    }

    private fun buildCandidate(base: String?, n: Int): String {
        val trimmed = base?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) return "($n)"
        val suffix = " ($n)"
        return if (trimmed.length + suffix.length <= MAX_DESCRIPTION_LENGTH) {
            trimmed + suffix
        } else {
            trimmed.take(MAX_DESCRIPTION_LENGTH - suffix.length) + suffix
        }
    }
}
