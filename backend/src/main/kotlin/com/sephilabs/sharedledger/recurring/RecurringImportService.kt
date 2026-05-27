package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.CsvReader
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.import.AdjustedRow
import com.sephilabs.sharedledger.common.import.ExecuteResult
import com.sephilabs.sharedledger.common.import.InFileDuplicateResolver
import com.sephilabs.sharedledger.common.import.MAX_ERRORS_REPORTED
import com.sephilabs.sharedledger.common.import.MAX_SKIPPED_REPORTED
import com.sephilabs.sharedledger.common.import.PreviewSummary
import com.sephilabs.sharedledger.common.import.RowError
import com.sephilabs.sharedledger.common.import.SkippedRow
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.transaction.Direction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class RecurringImportService(
    private val repository: RecurringTemplateRepository,
    private val categoryService: CategoryService,
) {

    private val log = LoggerFactory.getLogger(RecurringImportService::class.java)

    private val expectedHeaders = listOf(
        "direction", "category_code", "amount", "description", "cadence",
        "day_of_week", "day_of_month", "month_of_year", "day_of_month_yearly",
        "start_date", "end_date", "active",
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary =
        parseAndValidate(householdId, input).toPreview()

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Recurring import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            log.warn("Recurring import aborted: {} validation errors", parsed.errors.size)
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (row in parsed.validRows) {
            val key = dedupKey(row)
            if (parsed.existingKeys.contains(key)) {
                val summary = rowSummary(row)
                log.info("Recurring import skipped row {}: duplicate — {}", row.rowNumber, summary)
                if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, summary))
                continue
            }
            val template = RecurringTemplate(
                householdId = householdId,
                direction = row.direction,
                categoryCode = row.categoryCode,
                amount = Money.normalize(row.amount),
                description = row.description,
                cadence = row.cadence,
                dayOfMonth = row.dayOfMonth,
                dayOfWeek = row.dayOfWeek,
                monthOfYear = row.monthOfYear,
                dayOfMonthYearly = row.dayOfMonthYearly,
                startDate = row.startDate,
                endDate = row.endDate,
                active = row.active,
                createdByUserId = importer.id,
                updatedByUserId = importer.id,
            )
            repository.save(template)
            parsed.existingKeys.add(key)
            inserted++
        }
        val totalSkipped = parsed.validRows.size - inserted
        log.info("Recurring import finished: inserted={} skipped={} totalRows={}", inserted, totalSkipped, parsed.totalRows)
        return ExecuteResult(
            inserted = inserted,
            skipped = totalSkipped,
            replaced = 0,
            skippedRows = skippedList,
            truncatedSkipped = totalSkipped > skippedList.size,
            adjustedDescriptions = parsed.adjustedRows,
            adjustedCount = parsed.adjustedCount,
            truncatedAdjusted = parsed.adjustedCount > parsed.adjustedRows.size,
        )
    }

    private fun parseAndValidate(householdId: UUID, input: InputStream): ParsedFile {
        val parsed = CsvReader.parse(input)
        if (parsed.parseError != null) {
            throw AppException.badRequest("IMPORT_PARSE_FAILED")
        }
        val headerErrors = validateHeaders(parsed.headers)
        if (headerErrors.isNotEmpty()) {
            return ParsedFile(
                totalRows = parsed.rows.size,
                errors = headerErrors,
                validRows = mutableListOf(),
                existingKeys = mutableSetOf(),
                adjustedRows = emptyList(),
                adjustedCount = 0,
            )
        }

        val errors = mutableListOf<RowError>()
        val validRows = mutableListOf<ParsedRow>()

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val directionStr = raw["direction"]?.lowercase().orEmpty()
            val direction = runCatching { Direction.valueOf(directionStr) }.getOrNull()
            if (direction == null) {
                rowErrors += RowError(rowNo, "IMPORT_DIRECTION_INVALID", "direction", raw["direction"])
            }

            val categoryCode = raw["category_code"]?.takeIf { it.isNotEmpty() }
            if (categoryCode == null) {
                rowErrors += RowError(rowNo, "IMPORT_CATEGORY_UNKNOWN", "category_code", null)
            } else if (direction != null) {
                val view = categoryService.find(householdId, categoryCode)
                if (view == null) {
                    rowErrors += RowError(rowNo, "IMPORT_CATEGORY_UNKNOWN", "category_code", categoryCode)
                } else if (view.kind != direction.name) {
                    rowErrors += RowError(rowNo, "IMPORT_CATEGORY_DIRECTION_MISMATCH", "category_code", categoryCode)
                }
            }

            val amount = Csv.parseDecimal(raw["amount"].orEmpty())
            if (amount == null || amount <= BigDecimal.ZERO) {
                rowErrors += RowError(rowNo, "IMPORT_AMOUNT_INVALID", "amount", raw["amount"])
            }

            val description = raw["description"]?.takeIf { it.isNotEmpty() }
            if (description != null && description.length > 500) {
                rowErrors += RowError(rowNo, "IMPORT_DESCRIPTION_TOO_LONG", "description", null)
            }

            val cadenceStr = raw["cadence"]?.lowercase().orEmpty()
            val cadence = runCatching { Cadence.valueOf(cadenceStr) }.getOrNull()
            if (cadence == null) {
                rowErrors += RowError(rowNo, "IMPORT_CADENCE_INVALID", "cadence", raw["cadence"])
            }

            val dayOfWeek = parseShort(raw["day_of_week"])
            val dayOfMonth = parseShort(raw["day_of_month"])
            val monthOfYear = parseShort(raw["month_of_year"])
            val dayOfMonthYearly = parseShort(raw["day_of_month_yearly"])

            if (raw["day_of_week"].orEmpty().isNotEmpty() && (dayOfWeek == null || dayOfWeek !in 1..7)) {
                rowErrors += RowError(rowNo, "IMPORT_DAY_OF_WEEK_INVALID", "day_of_week", raw["day_of_week"])
            }
            if (raw["day_of_month"].orEmpty().isNotEmpty() && (dayOfMonth == null || dayOfMonth !in 1..31)) {
                rowErrors += RowError(rowNo, "IMPORT_DAY_OF_MONTH_INVALID", "day_of_month", raw["day_of_month"])
            }
            if (raw["month_of_year"].orEmpty().isNotEmpty() && (monthOfYear == null || monthOfYear !in 1..12)) {
                rowErrors += RowError(rowNo, "IMPORT_MONTH_OF_YEAR_INVALID", "month_of_year", raw["month_of_year"])
            }
            if (raw["day_of_month_yearly"].orEmpty().isNotEmpty() && (dayOfMonthYearly == null || dayOfMonthYearly !in 1..31)) {
                rowErrors += RowError(rowNo, "IMPORT_DAY_OF_MONTH_INVALID", "day_of_month_yearly", raw["day_of_month_yearly"])
            }

            if (cadence != null) {
                val cadenceOk = when (cadence) {
                    Cadence.weekly -> dayOfWeek != null && dayOfMonth == null && monthOfYear == null && dayOfMonthYearly == null
                    Cadence.monthly -> dayOfMonth != null && dayOfWeek == null && monthOfYear == null && dayOfMonthYearly == null
                    Cadence.yearly -> monthOfYear != null && dayOfMonthYearly != null && dayOfWeek == null && dayOfMonth == null
                }
                if (!cadenceOk) {
                    rowErrors += RowError(rowNo, "IMPORT_CADENCE_FIELDS_INVALID", "cadence", cadence.name)
                }
            }

            val startDate = parseDate(raw["start_date"])
            if (startDate == null) {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "start_date", raw["start_date"])
            }

            val endDateStr = raw["end_date"].orEmpty()
            val endDate = if (endDateStr.isEmpty()) null else parseDate(endDateStr)
            if (endDateStr.isNotEmpty() && endDate == null) {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "end_date", endDateStr)
            }
            if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
                rowErrors += RowError(rowNo, "IMPORT_END_BEFORE_START", "end_date", endDateStr)
            }

            val activeStr = raw["active"]?.lowercase().orEmpty()
            val active = when (activeStr) {
                "", "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            if (active == null) {
                rowErrors += RowError(rowNo, "IMPORT_ACTIVE_INVALID", "active", raw["active"])
            }

            if (rowErrors.isEmpty()) {
                validRows += ParsedRow(
                    rowNumber = rowNo,
                    direction = direction!!,
                    categoryCode = categoryCode!!,
                    amount = Money.normalize(amount!!),
                    description = description,
                    cadence = cadence!!,
                    dayOfWeek = if (cadence == Cadence.weekly) dayOfWeek else null,
                    dayOfMonth = if (cadence == Cadence.monthly) dayOfMonth else null,
                    monthOfYear = if (cadence == Cadence.yearly) monthOfYear else null,
                    dayOfMonthYearly = if (cadence == Cadence.yearly) dayOfMonthYearly else null,
                    startDate = startDate!!,
                    endDate = endDate,
                    active = active!!,
                )
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existingKeys: MutableSet<String> = if (validRows.isNotEmpty()) {
            repository.findAllByHouseholdId(householdId)
                .mapTo(mutableSetOf()) { dedupKeyFromEntity(it) }
        } else mutableSetOf()

        val resolution = InFileDuplicateResolver.resolveAll(
            rows = validRows,
            existingKeys = existingKeys,
            keyOf = { row, desc -> dedupKey(row.copy(description = desc)) },
            descriptionOf = { it.description },
            rowNumberOf = { it.rowNumber },
            withDescription = { row, desc -> row.copy(description = desc) },
            summarize = { rowSummary(it) },
        )

        return ParsedFile(
            totalRows = parsed.rows.size,
            errors = errors,
            validRows = validRows,
            existingKeys = existingKeys,
            adjustedRows = resolution.adjustedRows,
            adjustedCount = resolution.adjustedCount,
        )
    }

    private fun rowSummary(row: ParsedRow): String {
        val cadenceField = when (row.cadence) {
            Cadence.weekly -> "dow=${row.dayOfWeek}"
            Cadence.monthly -> "dom=${row.dayOfMonth}"
            Cadence.yearly -> "moy=${row.monthOfYear}/dom=${row.dayOfMonthYearly}"
        }
        val desc = row.description?.take(40) ?: ""
        return "${row.direction.name} · ${row.categoryCode} · ${Money.normalize(row.amount).toPlainString()} · " +
            "${row.cadence.name}($cadenceField) · ${row.startDate}${if (desc.isNotEmpty()) " · $desc" else ""}"
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

    private fun parseShort(value: String?): Short? {
        if (value.isNullOrEmpty()) return null
        return value.toShortOrNull()
    }

    private fun dedupKey(row: ParsedRow): String {
        val desc = row.description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return listOf(
            row.direction.name,
            row.categoryCode,
            Money.normalize(row.amount).toPlainString(),
            row.cadence.name,
            row.dayOfWeek?.toString() ?: "",
            row.dayOfMonth?.toString() ?: "",
            row.monthOfYear?.toString() ?: "",
            row.dayOfMonthYearly?.toString() ?: "",
            row.startDate.toString(),
            row.endDate?.toString() ?: "",
            row.active.toString(),
            desc,
        ).joinToString("|")
    }

    private fun dedupKeyFromEntity(t: RecurringTemplate): String {
        val desc = t.description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return listOf(
            t.direction.name,
            t.categoryCode,
            Money.normalize(t.amount).toPlainString(),
            t.cadence.name,
            t.dayOfWeek?.toString() ?: "",
            t.dayOfMonth?.toString() ?: "",
            t.monthOfYear?.toString() ?: "",
            t.dayOfMonthYearly?.toString() ?: "",
            t.startDate.toString(),
            t.endDate?.toString() ?: "",
            t.active.toString(),
            desc,
        ).joinToString("|")
    }

    private fun addErrors(sink: MutableList<RowError>, more: List<RowError>) {
        for (e in more) {
            if (sink.size >= MAX_ERRORS_REPORTED) return
            sink.add(e)
        }
    }

    private data class ParsedRow(
        val rowNumber: Int,
        val direction: Direction,
        val categoryCode: String,
        val amount: BigDecimal,
        val description: String?,
        val cadence: Cadence,
        val dayOfWeek: Short?,
        val dayOfMonth: Short?,
        val monthOfYear: Short?,
        val dayOfMonthYearly: Short?,
        val startDate: LocalDate,
        val endDate: LocalDate?,
        val active: Boolean,
    )

    private inner class ParsedFile(
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: MutableList<ParsedRow>,
        val existingKeys: MutableSet<String>,
        val adjustedRows: List<AdjustedRow>,
        val adjustedCount: Int,
    ) {
        fun toPreview(): PreviewSummary {
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (row in validRows) {
                if (existingKeys.contains(dedupKey(row))) {
                    wouldSkip++
                    if (skippedList.size < MAX_SKIPPED_REPORTED) {
                        skippedList.add(SkippedRow(row.rowNumber, rowSummary(row)))
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
                adjustedDescriptions = adjustedRows,
                adjustedCount = adjustedCount,
                truncatedAdjusted = adjustedCount > adjustedRows.size,
            )
        }
    }
}
