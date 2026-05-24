package com.sharedledger.common

import java.math.BigDecimal

/**
 * European CSV: ';' separator + ',' decimal. Opens directly in Excel for ES/NL/DE locales
 * without prompting for column splitting. Programmatic readers (pandas, csv libs) work
 * with `sep=";"` and `decimal=","`.
 */
object Csv {

    const val SEPARATOR: Char = ';'
    private const val EOL = "\r\n"

    fun row(vararg fields: Any?): String =
        fields.joinToString(SEPARATOR.toString()) { escape(it?.toString() ?: "") } + EOL

    fun decimal(value: BigDecimal): String = value.toPlainString().replace('.', ',')

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
