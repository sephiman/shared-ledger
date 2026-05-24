package com.sharedledger.transaction

import com.sharedledger.catalog.CategoryRepository
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
import com.sharedledger.observability.AppMetrics
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class TransactionImportService(
    private val repository: TransactionRepository,
    private val categories: CategoryRepository,
    private val metrics: AppMetrics,
) {

    @PersistenceContext
    private lateinit var em: EntityManager

    private val log = LoggerFactory.getLogger(TransactionImportService::class.java)

    private val expectedHeaders = listOf(
        "date", "direction", "category_code", "amount", "description", "created_at", "updated_at"
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary {
        val parsed = parseAndValidate(householdId, input)
        return parsed.toPreview()
    }

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Transaction import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            log.warn("Transaction import aborted: {} validation errors", parsed.errors.size)
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (row in parsed.validRows) {
            val key = dedupKey(row.date, row.direction, row.categoryCode, row.amount, row.description)
            if (parsed.existingKeys.contains(key)) {
                val summary = rowSummary(row.date, row.direction.name, row.categoryCode, row.amount, row.description)
                log.info("Transaction import skipped row {}: duplicate of existing — {}", row.rowNumber, summary)
                if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, summary))
                continue
            }
            val tx = Transaction(
                householdId = householdId,
                occurrenceDate = row.date,
                direction = row.direction,
                categoryCode = row.categoryCode,
                amount = row.amount,
                description = row.description,
                createdByUserId = importer.id,
                updatedByUserId = importer.id,
            )
            repository.save(tx)
            if (row.createdAt != null || row.updatedAt != null) {
                em.flush()
                em.createQuery("UPDATE Transaction t SET t.createdAt = :ca, t.updatedAt = :ua WHERE t.id = :id")
                    .setParameter("ca", row.createdAt ?: tx.createdAt)
                    .setParameter("ua", row.updatedAt ?: tx.updatedAt)
                    .setParameter("id", tx.id)
                    .executeUpdate()
            }
            parsed.existingKeys.add(key)
            metrics.transactionCreated(row.direction.name, parsed.categoryGroups[row.categoryCode] ?: "ungrouped")
            inserted++
        }
        val totalSkipped = parsed.validRows.size - inserted
        log.info("Transaction import finished: inserted={} skipped={} totalRows={}", inserted, totalSkipped, parsed.totalRows)
        return ExecuteResult(
            inserted = inserted,
            skipped = totalSkipped,
            replaced = 0,
            skippedRows = skippedList,
            truncatedSkipped = totalSkipped > skippedList.size,
        )
    }

    private fun rowSummary(date: LocalDate, direction: String, code: String, amount: BigDecimal, desc: String?): String {
        val d = desc?.take(40) ?: ""
        return "$date · $direction · $code · ${Money.normalize(amount).toPlainString()}${if (d.isNotEmpty()) " · $d" else ""}"
    }

    private fun parseAndValidate(householdId: UUID, input: InputStream): ParsedFile {
        val parsed = CsvReader.parse(input)
        if (parsed.parseError != null) {
            throw AppException.badRequest("IMPORT_PARSE_FAILED")
        }
        val headerErrors = validateHeaders(parsed.headers)
        if (headerErrors.isNotEmpty()) {
            return ParsedFile(
                headers = parsed.headers,
                totalRows = parsed.rows.size,
                errors = headerErrors,
                validRows = emptyList(),
                existingKeys = mutableSetOf(),
                categoryGroups = emptyMap(),
                sumIncome = BigDecimal.ZERO,
                sumExpense = BigDecimal.ZERO,
                dateFrom = null,
                dateTo = null,
            )
        }

        val catalog = categories.findAll().associateBy { it.code }
        val errors = mutableListOf<RowError>()
        val validRows = mutableListOf<ParsedRow>()
        var sumIncome = BigDecimal.ZERO
        var sumExpense = BigDecimal.ZERO
        var minDate: LocalDate? = null
        var maxDate: LocalDate? = null

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2 // header is line 1
            val rowErrors = mutableListOf<RowError>()

            val date = parseDate(raw["date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "date", raw["date"])
                null
            }
            if (date != null && date.isAfter(LocalDate.now().plusYears(1))) {
                rowErrors += RowError(rowNo, "IMPORT_DATE_FAR_FUTURE", "date", raw["date"])
            }

            val directionStr = raw["direction"]?.lowercase().orEmpty()
            val direction = runCatching { Direction.valueOf(directionStr) }.getOrNull()
            if (direction == null) {
                rowErrors += RowError(rowNo, "IMPORT_DIRECTION_INVALID", "direction", raw["direction"])
            }

            val categoryCode = raw["category_code"].orEmpty()
            val category = catalog[categoryCode]
            if (category == null) {
                rowErrors += RowError(rowNo, "IMPORT_CATEGORY_UNKNOWN", "category_code", categoryCode)
            } else if (direction != null && category.kind != direction.name) {
                rowErrors += RowError(rowNo, "IMPORT_CATEGORY_DIRECTION_MISMATCH", "category_code", categoryCode)
            }

            val amount = Csv.parseDecimal(raw["amount"].orEmpty())
            if (amount == null || amount <= BigDecimal.ZERO) {
                rowErrors += RowError(rowNo, "IMPORT_AMOUNT_INVALID", "amount", raw["amount"])
            }

            val description = raw["description"]?.takeIf { it.isNotEmpty() }
            if (description != null && description.length > 500) {
                rowErrors += RowError(rowNo, "IMPORT_DESCRIPTION_TOO_LONG", "description", null)
            }

            val createdAt = raw["created_at"].orEmpty().let { if (it.isEmpty()) null else parseInstant(it) }
            if (raw["created_at"].orEmpty().isNotEmpty() && createdAt == null) {
                rowErrors += RowError(rowNo, "IMPORT_TIMESTAMP_INVALID", "created_at", raw["created_at"])
            }
            val updatedAt = raw["updated_at"].orEmpty().let { if (it.isEmpty()) null else parseInstant(it) }
            if (raw["updated_at"].orEmpty().isNotEmpty() && updatedAt == null) {
                rowErrors += RowError(rowNo, "IMPORT_TIMESTAMP_INVALID", "updated_at", raw["updated_at"])
            }

            if (rowErrors.isEmpty()) {
                val normalizedAmount = Money.normalize(amount!!)
                validRows += ParsedRow(
                    rowNumber = rowNo,
                    date = date!!,
                    direction = direction!!,
                    categoryCode = categoryCode,
                    amount = normalizedAmount,
                    description = description,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
                when (direction) {
                    Direction.income -> sumIncome += normalizedAmount
                    Direction.expense -> sumExpense += normalizedAmount
                }
                if (minDate == null || date.isBefore(minDate)) minDate = date
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existingKeys = if (validRows.isNotEmpty()) {
            val from = minDate!!
            val to = maxDate!!
            repository.findByHouseholdIdAndOccurrenceDateBetween(householdId, from, to)
                .mapTo(mutableSetOf()) { dedupKey(it.occurrenceDate, it.direction, it.categoryCode, it.amount, it.description) }
        } else mutableSetOf()

        val categoryGroups = catalog.mapValues { it.value.groupCode ?: "ungrouped" }

        return ParsedFile(
            headers = parsed.headers,
            totalRows = parsed.rows.size,
            errors = errors,
            validRows = validRows,
            existingKeys = existingKeys,
            categoryGroups = categoryGroups,
            sumIncome = sumIncome,
            sumExpense = sumExpense,
            dateFrom = minDate,
            dateTo = maxDate,
        )
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

    private fun parseInstant(value: String): Instant? {
        return try { Instant.parse(value) } catch (_: DateTimeParseException) { null }
    }

    private fun dedupKey(
        date: LocalDate,
        direction: Direction,
        categoryCode: String,
        amount: BigDecimal,
        description: String?,
    ): String {
        val desc = description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return "${date}|${direction.name}|${categoryCode}|${Money.normalize(amount).toPlainString()}|$desc"
    }

    private fun addErrors(sink: MutableList<RowError>, more: List<RowError>) {
        for (e in more) {
            if (sink.size >= MAX_ERRORS_REPORTED) return
            sink.add(e)
        }
    }

    private data class ParsedRow(
        val rowNumber: Int,
        val date: LocalDate,
        val direction: Direction,
        val categoryCode: String,
        val amount: BigDecimal,
        val description: String?,
        val createdAt: Instant?,
        val updatedAt: Instant?,
    )

    private data class ParsedFile(
        val headers: List<String>,
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: List<ParsedRow>,
        val existingKeys: MutableSet<String>,
        val categoryGroups: Map<String, String>,
        val sumIncome: BigDecimal,
        val sumExpense: BigDecimal,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
    ) {
        fun toPreview(): PreviewSummary {
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (r in validRows) {
                if (existingKeys.contains(buildKey(r.date, r.direction.name, r.categoryCode, r.amount, r.description))) {
                    wouldSkip++
                    if (skippedList.size < MAX_SKIPPED_REPORTED) {
                        val desc = r.description?.take(40) ?: ""
                        val summary = "${r.date} · ${r.direction.name} · ${r.categoryCode} · " +
                            "${Money.normalize(r.amount).toPlainString()}${if (desc.isNotEmpty()) " · $desc" else ""}"
                        skippedList.add(SkippedRow(r.rowNumber, summary))
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
                sumIncome = sumIncome,
                sumExpense = sumExpense,
                dateFrom = dateFrom?.toString(),
                dateTo = dateTo?.toString(),
            )
        }

        private fun buildKey(date: LocalDate, dir: String, code: String, amt: BigDecimal, desc: String?): String {
            val d = desc?.trim()?.takeIf { it.isNotEmpty() } ?: ""
            return "${date}|$dir|$code|${Money.normalize(amt).toPlainString()}|$d"
        }
    }
}
