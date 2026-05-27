package com.sephilabs.sharedledger.common

import com.fasterxml.jackson.databind.MappingIterator
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvParser
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets

/**
 * Parses the European-CSV dialect used by our exports: `;` separator, `,` decimal, optional UTF-8 BOM,
 * CRLF/LF tolerant, RFC 4180 quoting (escape `"` by doubling). Returns each row as a map keyed by the
 * lowercased, trimmed header name.
 */
object CsvReader {

    private val mapper: CsvMapper = CsvMapper().apply {
        enable(CsvParser.Feature.TRIM_SPACES)
        enable(CsvParser.Feature.SKIP_EMPTY_LINES)
    }

    fun parse(input: InputStream): ParseResult {
        val reader = InputStreamReader(stripBom(input), StandardCharsets.UTF_8)
        val schema = CsvSchema.emptySchema()
            .withColumnSeparator(';')
            .withQuoteChar('"')
            .withHeader()

        val rows = mutableListOf<Map<String, String>>()
        val headers: List<String>
        try {
            val iter: MappingIterator<Map<String, Any?>> =
                mapper.readerFor(Map::class.java).with(schema).readValues<Map<String, Any?>>(reader)
            iter.use { mi ->
                while (mi.hasNext()) {
                    val row = mi.next()
                    rows.add(row.entries.associate { (k, v) ->
                        k.lowercase().trim() to (v?.toString()?.trim() ?: "")
                    })
                }
            }
            headers = rows.firstOrNull()?.keys?.toList() ?: emptyList()
        } catch (ex: Exception) {
            return ParseResult(headers = emptyList(), rows = emptyList(), parseError = ex.message ?: "parse error")
        }
        return ParseResult(headers = headers, rows = rows, parseError = null)
    }

    private fun stripBom(input: InputStream): InputStream {
        val pushback = PushbackInputStream(input, 3)
        val bom = ByteArray(3)
        val read = pushback.read(bom, 0, 3)
        if (read == 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()) {
            return pushback
        }
        if (read > 0) pushback.unread(bom, 0, read)
        return pushback
    }

    data class ParseResult(
        val headers: List<String>,
        val rows: List<Map<String, String>>,
        val parseError: String?,
    )
}
