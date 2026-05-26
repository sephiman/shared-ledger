package com.sharedledger.loan

import com.sharedledger.common.Csv
import com.sharedledger.common.CsvReader
import com.sharedledger.common.Money
import com.sharedledger.common.errors.AppException
import com.sharedledger.common.import.AdjustedRow
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
class LoanImportService(
    private val loans: LoanRepository,
) {

    private val log = LoggerFactory.getLogger(LoanImportService::class.java)

    private val expectedHeaders = listOf(
        "borrower_name", "principal_amount", "start_date", "interest_type",
        "annual_interest_rate", "compounding_period", "description", "status",
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary =
        parseAndValidate(householdId, input).toPreview()

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Loan import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (row in parsed.validRows) {
            val key = dedupKey(row.borrowerName, row.startDate, row.principalAmount)
            if (parsed.existingKeys.contains(key)) {
                val summary = rowSummary(row)
                if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, summary))
                continue
            }
            val loan = Loan(
                householdId = householdId,
                borrowerName = row.borrowerName,
                principalAmount = row.principalAmount,
                startDate = row.startDate,
                description = row.description,
                interestType = row.interestType,
                annualInterestRate = row.annualInterestRate,
                compoundingPeriod = row.compoundingPeriod,
                status = row.status,
                closedDate = if (row.status == LoanStatus.active) null else row.startDate,
                createdByUserId = importer.id,
                updatedByUserId = importer.id,
            )
            loans.save(loan)
            parsed.existingKeys.add(key)
            inserted++
        }
        val totalSkipped = parsed.validRows.size - inserted
        log.info("Loan import finished: inserted={} skipped={} total={}", inserted, totalSkipped, parsed.totalRows)
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
        val validRows = mutableListOf<ParsedLoanRow>()

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val borrowerName = raw["borrower_name"].orEmpty().trim()
            if (borrowerName.isEmpty()) {
                rowErrors += RowError(rowNo, "IMPORT_BORROWER_REQUIRED", "borrower_name", raw["borrower_name"])
            } else if (borrowerName.length > 120) {
                rowErrors += RowError(rowNo, "IMPORT_BORROWER_TOO_LONG", "borrower_name", null)
            }

            val principal = Csv.parseDecimal(raw["principal_amount"].orEmpty())
            if (principal == null || principal <= BigDecimal.ZERO) {
                rowErrors += RowError(rowNo, "IMPORT_AMOUNT_INVALID", "principal_amount", raw["principal_amount"])
            }

            val startDate = parseDate(raw["start_date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "start_date", raw["start_date"])
                null
            }

            val interestTypeStr = raw["interest_type"]?.lowercase().orEmpty()
            val interestType = runCatching { InterestType.valueOf(interestTypeStr) }.getOrNull()
            if (interestType == null) {
                rowErrors += RowError(rowNo, "IMPORT_INTEREST_TYPE_INVALID", "interest_type", raw["interest_type"])
            }

            val rateRaw = raw["annual_interest_rate"].orEmpty()
            val rate = if (rateRaw.isBlank()) null else Csv.parseDecimal(rateRaw)
            if (interestType != null && interestType != InterestType.none) {
                if (rate == null || rate <= BigDecimal.ZERO) {
                    rowErrors += RowError(rowNo, "IMPORT_RATE_REQUIRED", "annual_interest_rate", rateRaw)
                }
            } else if (interestType == InterestType.none && rate != null && rate.signum() != 0) {
                rowErrors += RowError(rowNo, "IMPORT_RATE_NOT_ALLOWED", "annual_interest_rate", rateRaw)
            }

            val compoundRaw = raw["compounding_period"].orEmpty().lowercase()
            val compounding = if (compoundRaw.isBlank()) null
                else runCatching { CompoundingPeriod.valueOf(compoundRaw) }.getOrNull()
            if (interestType == InterestType.compound) {
                if (compounding == null) {
                    rowErrors += RowError(rowNo, "IMPORT_COMPOUNDING_INVALID", "compounding_period", raw["compounding_period"])
                }
            } else if (compounding != null) {
                rowErrors += RowError(rowNo, "IMPORT_COMPOUNDING_NOT_ALLOWED", "compounding_period", raw["compounding_period"])
            }

            val description = raw["description"]?.takeIf { it.isNotBlank() }
            if (description != null && description.length > 500) {
                rowErrors += RowError(rowNo, "IMPORT_DESCRIPTION_TOO_LONG", "description", null)
            }

            val statusRaw = raw["status"].orEmpty().lowercase()
            val status: LoanStatus = if (statusRaw.isBlank()) LoanStatus.active
            else runCatching { LoanStatus.valueOf(statusRaw) }.getOrNull() ?: run {
                rowErrors += RowError(rowNo, "IMPORT_STATUS_INVALID", "status", raw["status"])
                LoanStatus.active
            }

            if (rowErrors.isEmpty()) {
                validRows += ParsedLoanRow(
                    rowNumber = rowNo,
                    borrowerName = borrowerName,
                    principalAmount = Money.normalize(principal!!),
                    startDate = startDate!!,
                    description = description,
                    interestType = interestType!!,
                    annualInterestRate = rate?.setScale(4, java.math.RoundingMode.HALF_EVEN),
                    compoundingPeriod = if (interestType == InterestType.compound) compounding else null,
                    status = status,
                )
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existingKeys = loans.findAllByHouseholdId(householdId)
            .mapTo(mutableSetOf()) { dedupKey(it.borrowerName, it.startDate, it.principalAmount) }

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

    private fun dedupKey(borrower: String, startDate: LocalDate, principal: BigDecimal): String =
        "${borrower.trim().lowercase()}|$startDate|${Money.normalize(principal).toPlainString()}"

    private fun rowSummary(row: ParsedLoanRow): String =
        "${row.borrowerName} · ${row.startDate} · ${Money.normalize(row.principalAmount).toPlainString()}"

    private fun addErrors(sink: MutableList<RowError>, more: List<RowError>) {
        for (e in more) {
            if (sink.size >= MAX_ERRORS_REPORTED) return
            sink.add(e)
        }
    }

    private data class ParsedLoanRow(
        val rowNumber: Int,
        val borrowerName: String,
        val principalAmount: BigDecimal,
        val startDate: LocalDate,
        val description: String?,
        val interestType: InterestType,
        val annualInterestRate: BigDecimal?,
        val compoundingPeriod: CompoundingPeriod?,
        val status: LoanStatus,
    )

    private inner class ParsedFile(
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: MutableList<ParsedLoanRow>,
        val existingKeys: MutableSet<String>,
    ) {
        fun toPreview(): PreviewSummary {
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (r in validRows) {
                val key = dedupKey(r.borrowerName, r.startDate, r.principalAmount)
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
                adjustedDescriptions = emptyList<AdjustedRow>(),
            )
        }
    }
}
