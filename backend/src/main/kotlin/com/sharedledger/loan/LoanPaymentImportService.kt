package com.sharedledger.loan

import com.sharedledger.common.Csv
import com.sharedledger.common.CsvReader
import com.sharedledger.common.Money
import com.sharedledger.common.errors.AppException
import com.sharedledger.common.import.ExecuteResult
import com.sharedledger.common.import.MAX_ERRORS_REPORTED
import com.sharedledger.common.import.MAX_SKIPPED_REPORTED
import com.sharedledger.common.import.PreviewSummary
import com.sharedledger.common.import.RowError
import com.sharedledger.common.import.SkippedRow
import com.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class LoanPaymentImportService(
    private val loans: LoanRepository,
    private val payments: LoanPaymentRepository,
) {

    private val log = LoggerFactory.getLogger(LoanPaymentImportService::class.java)

    private val expectedHeaders = listOf(
        "borrower_name", "loan_start_date", "payment_date", "amount", "description",
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary =
        parseAndValidate(householdId, input).toPreview()

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Loan payment import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (row in parsed.validRows) {
            val key = dedupKey(row.loanId, row.paymentDate, row.amount, row.description)
            if (parsed.existingKeys.contains(key)) {
                if (skippedList.size < MAX_SKIPPED_REPORTED) {
                    skippedList.add(SkippedRow(row.rowNumber, rowSummary(row)))
                }
                continue
            }
            val payment = LoanPayment(
                loanId = row.loanId,
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
        val loansByKey = loans.findAllByHouseholdId(householdId)
            .groupBy { "${it.borrowerName.trim().lowercase()}|${it.startDate}" }

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val borrowerName = raw["borrower_name"].orEmpty().trim()
            if (borrowerName.isEmpty()) {
                rowErrors += RowError(rowNo, "IMPORT_BORROWER_REQUIRED", "borrower_name", raw["borrower_name"])
            }

            val loanStartDate = parseDate(raw["loan_start_date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "loan_start_date", raw["loan_start_date"])
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

            var loanId: UUID? = null
            if (borrowerName.isNotEmpty() && loanStartDate != null) {
                val matches = loansByKey["${borrowerName.lowercase()}|$loanStartDate"].orEmpty()
                when {
                    matches.isEmpty() -> rowErrors += RowError(rowNo, "IMPORT_LOAN_UNKNOWN", "borrower_name", borrowerName)
                    matches.size > 1 -> rowErrors += RowError(rowNo, "IMPORT_LOAN_AMBIGUOUS", "borrower_name", borrowerName)
                    else -> loanId = matches.single().id
                }
            }

            if (loanId != null && paymentDate != null && paymentDate.isBefore(loanStartDate)) {
                rowErrors += RowError(rowNo, "IMPORT_PAYMENT_BEFORE_START", "payment_date", paymentDate.toString())
            }

            if (rowErrors.isEmpty()) {
                validRows += ParsedPaymentRow(
                    rowNumber = rowNo,
                    borrowerName = borrowerName,
                    loanStartDate = loanStartDate!!,
                    loanId = loanId!!,
                    paymentDate = paymentDate!!,
                    amount = Money.normalize(amount!!),
                    description = description,
                )
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existingKeys = if (validRows.isNotEmpty()) {
            val loanIds = validRows.map { it.loanId }.toSet()
            payments.findAllByLoanIdsOrderByPaymentDateAsc(loanIds)
                .mapTo(mutableSetOf()) { dedupKey(it.loanId, it.paymentDate, it.amount, it.description) }
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

    private fun dedupKey(loanId: UUID, date: LocalDate, amount: BigDecimal, description: String?): String {
        val desc = description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return "$loanId|$date|${Money.normalize(amount).toPlainString()}|$desc"
    }

    private fun rowSummary(row: ParsedPaymentRow): String {
        val desc = row.description?.take(40)?.let { " · $it" }.orEmpty()
        return "${row.borrowerName} · ${row.loanStartDate} · ${row.paymentDate} · ${Money.normalize(row.amount).toPlainString()}$desc"
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
        val loanStartDate: LocalDate,
        val loanId: UUID,
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
                val key = dedupKey(r.loanId, r.paymentDate, r.amount, r.description)
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
