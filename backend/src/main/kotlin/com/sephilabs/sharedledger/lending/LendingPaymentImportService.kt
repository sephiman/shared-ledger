package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.CsvReader
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.import.ExecuteResult
import com.sephilabs.sharedledger.common.import.MAX_ERRORS_REPORTED
import com.sephilabs.sharedledger.common.import.MAX_SKIPPED_REPORTED
import com.sephilabs.sharedledger.common.import.PreviewSummary
import com.sephilabs.sharedledger.common.import.RowError
import com.sephilabs.sharedledger.common.import.SkippedRow
import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class LendingPaymentImportService(
    private val lendings: LendingRepository,
    private val payments: LendingPaymentRepository,
) {

    private val log = LoggerFactory.getLogger(LendingPaymentImportService::class.java)

    private val expectedHeaders = listOf(
        "borrower_name", "lending_start_date", "payment_date", "amount", "description",
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary =
        parseAndValidate(householdId, input).toPreview()

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Lending payment import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (row in parsed.validRows) {
            val key = dedupKey(row.lendingId, row.paymentDate, row.amount, row.description)
            if (parsed.existingKeys.contains(key)) {
                if (skippedList.size < MAX_SKIPPED_REPORTED) {
                    skippedList.add(SkippedRow(row.rowNumber, rowSummary(row)))
                }
                continue
            }
            val payment = LendingPayment(
                lendingId = row.lendingId,
                paymentDate = row.paymentDate,
                amount = row.amount,
                description = row.description,
                scheduleId = null,
                createdByUserId = importer.id,
                updatedByUserId = importer.id,
            )
            payments.save(payment)
            parsed.existingKeys.add(key)
            inserted++
        }
        val totalSkipped = parsed.validRows.size - inserted
        return ExecuteResult(
            inserted = inserted,
            skipped = totalSkipped,
            replaced = 0,
            skippedRows = skippedList,
            truncatedSkipped = totalSkipped > skippedList.size,
        )
    }

    private fun parseAndValidate(householdId: UUID, input: InputStream): ParsedFile {
        val parsed = CsvReader.parse(input)
        if (parsed.parseError != null) throw AppException.badRequest("IMPORT_PARSE_FAILED")
        val headerErrors = validateHeaders(parsed.headers)
        if (headerErrors.isNotEmpty()) {
            return ParsedFile(parsed.rows.size, headerErrors, mutableListOf(), mutableSetOf())
        }

        val errors = mutableListOf<RowError>()
        val validRows = mutableListOf<ParsedPaymentRow>()
        val lendingsByKey = lendings.findAllByHouseholdId(householdId)
            .groupBy { "${it.borrowerName.trim().lowercase()}|${it.startDate}" }

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val borrowerName = raw["borrower_name"].orEmpty().trim()
            if (borrowerName.isEmpty()) {
                rowErrors += RowError(rowNo, "IMPORT_BORROWER_REQUIRED", "borrower_name", raw["borrower_name"])
            }

            val lendingStartDate = parseDate(raw["lending_start_date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "lending_start_date", raw["lending_start_date"])
                null
            }

            val paymentDate = parseDate(raw["payment_date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "payment_date", raw["payment_date"])
                null
            }

            val amount = Csv.parseDecimal(raw["amount"].orEmpty())
            if (amount == null || amount <= BigDecimal.ZERO) {
                rowErrors += RowError(rowNo, "IMPORT_AMOUNT_INVALID", "amount", raw["amount"])
            }

            val description = raw["description"]?.takeIf { it.isNotBlank() }
            if (description != null && description.length > 500) {
                rowErrors += RowError(rowNo, "IMPORT_DESCRIPTION_TOO_LONG", "description", null)
            }

            var lendingId: UUID? = null
            if (borrowerName.isNotEmpty() && lendingStartDate != null) {
                val matches = lendingsByKey["${borrowerName.lowercase()}|$lendingStartDate"].orEmpty()
                when {
                    matches.isEmpty() -> rowErrors += RowError(rowNo, "IMPORT_LENDING_UNKNOWN", "borrower_name", borrowerName)
                    matches.size > 1 -> rowErrors += RowError(rowNo, "IMPORT_LENDING_AMBIGUOUS", "borrower_name", borrowerName)
                    else -> lendingId = matches.single().id
                }
            }

            if (lendingId != null && paymentDate != null && paymentDate.isBefore(lendingStartDate)) {
                rowErrors += RowError(rowNo, "IMPORT_PAYMENT_BEFORE_START", "payment_date", paymentDate.toString())
            }

            if (rowErrors.isEmpty()) {
                validRows += ParsedPaymentRow(
                    rowNumber = rowNo,
                    borrowerName = borrowerName,
                    lendingStartDate = lendingStartDate!!,
                    lendingId = lendingId!!,
                    paymentDate = paymentDate!!,
                    amount = Money.normalize(amount!!),
                    description = description,
                )
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existingKeys = if (validRows.isNotEmpty()) {
            val lendingIds = validRows.map { it.lendingId }.toSet()
            payments.findAllByLendingIdsOrderByPaymentDateAsc(lendingIds)
                .mapTo(mutableSetOf()) { dedupKey(it.lendingId, it.paymentDate, it.amount, it.description) }
        } else mutableSetOf()

        return ParsedFile(parsed.rows.size, errors, validRows, existingKeys)
    }

    private fun validateHeaders(actual: List<String>): List<RowError> {
        val missing = expectedHeaders - actual.toSet()
        val unknown = actual - expectedHeaders.toSet()
        if (missing.isEmpty() && unknown.isEmpty()) return emptyList()
        val args = (missing.map { "missing:$it" } + unknown.map { "unknown:$it" }).joinToString(", ")
        return listOf(RowError(1, "IMPORT_HEADER_INVALID", null, args))
    }

    private fun parseDate(value: String?): LocalDate? {
        if (value.isNullOrEmpty()) return null
        return try { LocalDate.parse(value) } catch (_: DateTimeParseException) { null }
    }

    private fun dedupKey(lendingId: UUID, date: LocalDate, amount: BigDecimal, description: String?): String {
        val desc = description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return "$lendingId|$date|${Money.normalize(amount).toPlainString()}|$desc"
    }

    private fun rowSummary(row: ParsedPaymentRow): String {
        val desc = row.description?.take(40)?.let { " · $it" }.orEmpty()
        return "${row.borrowerName} · ${row.lendingStartDate} · ${row.paymentDate} · ${Money.normalize(row.amount).toPlainString()}$desc"
    }

    private fun addErrors(sink: MutableList<RowError>, more: List<RowError>) {
        for (e in more) {
            if (sink.size >= MAX_ERRORS_REPORTED) return
            sink.add(e)
        }
    }

    private data class ParsedPaymentRow(
        val rowNumber: Int,
        val borrowerName: String,
        val lendingStartDate: LocalDate,
        val lendingId: UUID,
        val paymentDate: LocalDate,
        val amount: BigDecimal,
        val description: String?,
    )

    private inner class ParsedFile(
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: MutableList<ParsedPaymentRow>,
        val existingKeys: MutableSet<String>,
    ) {
        fun toPreview(): PreviewSummary {
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (r in validRows) {
                val key = dedupKey(r.lendingId, r.paymentDate, r.amount, r.description)
                if (existingKeys.contains(key)) {
                    wouldSkip++
                    if (skippedList.size < MAX_SKIPPED_REPORTED) {
                        skippedList.add(SkippedRow(r.rowNumber, rowSummary(r)))
                    }
                }
            }
            val wouldInsert = validRows.size - wouldSkip
            return PreviewSummary(
                totalRows = totalRows,
                wouldInsert = wouldInsert,
                wouldSkip = wouldSkip,
                wouldReplace = 0,
                errorCount = errors.size,
                errors = errors,
                truncatedErrors = errors.size >= MAX_ERRORS_REPORTED,
                skippedRows = skippedList,
                truncatedSkipped = wouldSkip > skippedList.size,
            )
        }
    }
}
