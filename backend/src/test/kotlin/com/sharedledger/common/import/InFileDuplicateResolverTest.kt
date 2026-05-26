package com.sharedledger.common.import

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InFileDuplicateResolverTest {

    private fun simpleKey(desc: String?): String = "row|${desc?.trim()?.takeIf { it.isNotEmpty() } ?: ""}"

    @Test
    fun `returns null when key is not yet seen`() {
        val seen = mutableSetOf<String>()
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = "Lunch",
            seenKeys = seen,
            existingKeys = emptySet(),
            keyOf = ::simpleKey,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `appends ( 2 ) to the second occurrence of the same description`() {
        val seen = mutableSetOf(simpleKey("Lunch"))
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = "Lunch",
            seenKeys = seen,
            existingKeys = emptySet(),
            keyOf = ::simpleKey,
        )
        assertThat(result).isEqualTo("Lunch (2)")
    }

    @Test
    fun `advances to ( 3 ) when both file and DB already have ( 2 )`() {
        val seen = mutableSetOf(simpleKey("Lunch"), simpleKey("Lunch (2)"))
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = "Lunch",
            seenKeys = seen,
            existingKeys = emptySet(),
            keyOf = ::simpleKey,
        )
        assertThat(result).isEqualTo("Lunch (3)")
    }

    @Test
    fun `skips suffixes that collide with existing DB rows`() {
        val seen = mutableSetOf(simpleKey("Lunch"))
        val existing = setOf(simpleKey("Lunch (2)"), simpleKey("Lunch (3)"))
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = "Lunch",
            seenKeys = seen,
            existingKeys = existing,
            keyOf = ::simpleKey,
        )
        assertThat(result).isEqualTo("Lunch (4)")
    }

    @Test
    fun `uses bare parens when initial description is null`() {
        val seen = mutableSetOf(simpleKey(null))
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = null,
            seenKeys = seen,
            existingKeys = emptySet(),
            keyOf = ::simpleKey,
        )
        assertThat(result).isEqualTo("(2)")
    }

    @Test
    fun `uses bare parens when initial description is blank`() {
        val seen = mutableSetOf(simpleKey(""))
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = "",
            seenKeys = seen,
            existingKeys = emptySet(),
            keyOf = ::simpleKey,
        )
        assertThat(result).isEqualTo("(2)")
    }

    @Test
    fun `truncates description from the end to keep total within 500 chars`() {
        val long = "x".repeat(500)
        val seen = mutableSetOf(simpleKey(long))
        val result = InFileDuplicateResolver.disambiguate(
            initialDescription = long,
            seenKeys = seen,
            existingKeys = emptySet(),
            keyOf = ::simpleKey,
        )
        assertThat(result).isNotNull
        assertThat(result!!.length).isLessThanOrEqualTo(500)
        assertThat(result).endsWith(" (2)")
        assertThat(result.removeSuffix(" (2)")).isEqualTo("x".repeat(496))
    }

    private data class FakeRow(val rowNumber: Int, val description: String?)

    private fun rowKey(row: FakeRow, desc: String?): String =
        "fixed|${desc?.trim()?.takeIf { it.isNotEmpty() } ?: ""}"

    private fun resolve(rows: MutableList<FakeRow>, existing: Set<String> = emptySet()) =
        InFileDuplicateResolver.resolveAll(
            rows = rows,
            existingKeys = existing,
            keyOf = ::rowKey,
            descriptionOf = { it.description },
            rowNumberOf = { it.rowNumber },
            withDescription = { row, desc -> row.copy(description = desc) },
            summarize = { row -> "row ${row.rowNumber}: ${row.description ?: ""}" },
        )

    @Test
    fun `resolveAll appends counters to in-file duplicates`() {
        val rows = mutableListOf(
            FakeRow(2, "Lunch"),
            FakeRow(3, "Lunch"),
            FakeRow(4, "Lunch"),
        )
        val result = resolve(rows)

        assertThat(rows.map { it.description }).containsExactly("Lunch", "Lunch (2)", "Lunch (3)")
        assertThat(result.adjustedCount).isEqualTo(2)
        assertThat(result.adjustedRows).hasSize(2)
        assertThat(result.adjustedRows[0].row).isEqualTo(3)
        assertThat(result.adjustedRows[0].newDescription).isEqualTo("Lunch (2)")
        assertThat(result.adjustedRows[1].row).isEqualTo(4)
        assertThat(result.adjustedRows[1].newDescription).isEqualTo("Lunch (3)")
    }

    @Test
    fun `resolveAll leaves duplicates of existing DB rows alone so a re-import is a no-op`() {
        // Simulates re-uploading the same CSV after a first successful import that produced
        // [Lunch, Lunch (2)] in the DB. The two rows in the file would otherwise be renamed
        // to Lunch (3) and inserted — duplicating the user's data on every retry.
        val rows = mutableListOf(
            FakeRow(2, "Lunch"),
            FakeRow(3, "Lunch"),
        )
        val existing = setOf(rowKey(FakeRow(0, "Lunch"), "Lunch"), rowKey(FakeRow(0, "Lunch (2)"), "Lunch (2)"))

        val result = resolve(rows, existing)

        // No disambiguation: both rows keep their original description so the DB-skip path can catch them.
        assertThat(rows.map { it.description }).containsExactly("Lunch", "Lunch")
        assertThat(result.adjustedCount).isEqualTo(0)
        assertThat(result.adjustedRows).isEmpty()
    }

    @Test
    fun `resolveAll respects maxReported but still increments full count`() {
        val rows = mutableListOf(
            FakeRow(2, "X"),
            FakeRow(3, "X"),
            FakeRow(4, "X"),
            FakeRow(5, "X"),
        )
        val result = InFileDuplicateResolver.resolveAll(
            rows = rows,
            existingKeys = emptySet(),
            keyOf = ::rowKey,
            descriptionOf = { it.description },
            rowNumberOf = { it.rowNumber },
            withDescription = { row, desc -> row.copy(description = desc) },
            summarize = { it.description ?: "" },
            maxReported = 2,
        )

        assertThat(result.adjustedCount).isEqualTo(3)
        assertThat(result.adjustedRows).hasSize(2)
    }
}
