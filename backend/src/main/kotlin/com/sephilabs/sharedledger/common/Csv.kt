package com.sephilabs.sharedledger.common

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** European CSV: ';' separator + ',' decimal, so exports open directly in Excel for ES/NL/DE locales.
 *  Programmatic readers need `sep=";"` and `decimal=","`. */
object Csv {

    const val SEPARATOR: Char = ';'
    private const val EOL = "\r\n"

    fun row(vararg fields: Any?): String =
        fields.joinToString(SEPARATOR.toString()) { escape(it?.toString() ?: "") } + EOL

    fun decimal(value: BigDecimal): String = value.toPlainString().replace('.', ',')

    /** Builds a download filename like `20260526-mi-casa-transactions.csv`. Falls back to `<date>-<suffix>.csv` when the name has no usable characters. */
    fun exportFilename(date: LocalDate, householdName: String, suffix: String): String {
        val datePart = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val sanitized = sanitizeNameForFilename(householdName)
        val namePart = if (sanitized.isNotEmpty()) "-$sanitized" else ""
        return "$datePart$namePart-$suffix.csv"
    }

    /** Wraps a CSV body in the standard `text/csv` attachment response used by every export endpoint. */
    fun download(householdName: String, suffix: String, body: String): ResponseEntity<String> {
        val filename = exportFilename(LocalDate.now(), householdName, suffix)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(body)
    }

    private fun sanitizeNameForFilename(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder()
        var lastDash = false
        for (c in decomposed) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
                sb.append(c.lowercaseChar())
                lastDash = false
            } else if (!lastDash && sb.isNotEmpty()) {
                sb.append('-')
                lastDash = true
            }
        }
        while (sb.isNotEmpty() && sb.last() == '-') sb.deleteCharAt(sb.length - 1)
        return sb.toString()
    }

    /** Parses a comma-decimal string into a BigDecimal, or returns null if malformed. */
    fun parseDecimal(value: String): BigDecimal? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return try {
            BigDecimal(trimmed.replace(',', '.'))
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun escape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuoting = value.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
