package com.sephilabs.sharedledger.transaction

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
import com.sephilabs.sharedledger.observability.AppMetrics
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
    private val categoryService: CategoryService,
    private val metrics: AppMetrics,
) {

    @PersistenceContext
    private lateinit var em: EntityManager

    private val log = LoggerFactory.getLogger(TransactionImportService::class.java)

    private val expectedHeaders = listOf(
        "date", "direction", "category_code", "amount", "description", "created_at", "updated_at"
    )

    /** Refunds added two columns. Files exported before that are still valid — a missing `is_refund`
     *  simply means every row is an ordinary transaction. */
    private val refundHeaders = expectedHeaders + listOf("is_refund", "refund_of_key")

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
        // Ids of the originals inserted by this run, so a refund can link to one that arrived with it.
        val insertedTargets = mutableMapOf<String, UUID>()

        // Originals first: a refund's target may be a row further down the same file.
        val (refundRows, plainRows) = parsed.validRows.partition { it.isRefund }
        for (row in plainRows + refundRows) {
            val key = TransactionKeys.dedupKey(row.date, row.direction, row.categoryCode, row.amount, row.description)
            if (parsed.existingKeys.contains(key)) {
                val summary = rowSummary(row.date, row.direction.name, row.categoryCode, row.amount, row.description)
                log.info("Transaction import skipped row {}: duplicate of existing — {}", row.rowNumber, summary)
                if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, summary))
                continue
            }
            val refundOf = row.refundOfKey?.let { wanted ->
                // Null in the map means "a row in this file", which by now has been inserted.
                if (parsed.refundTargets.containsKey(wanted)) parsed.refundTargets[wanted] ?: insertedTargets[wanted]
                else null
            }
            val tx = Transaction(
                householdId = householdId,
                occurrenceDate = row.date,
                direction = row.direction,
                categoryCode = row.categoryCode,
                amount = row.amount,
                description = row.description,
                isRefund = row.isRefund,
                refundOfTransactionId = refundOf,
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
            // Keyed as the file wrote it, since that is what a refund_of_key names.
            if (!row.isRefund && row.direction == Direction.expense) insertedTargets.putIfAbsent(row.originalKey, tx.id)
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
            adjustedDescriptions = parsed.adjustedRows,
            adjustedCount = parsed.adjustedCount,
            truncatedAdjusted = parsed.adjustedCount > parsed.adjustedRows.size,
            droppedRefundLinks = parsed.droppedRefundLinks,
            droppedRefundLinkCount = parsed.droppedRefundLinkCount,
            truncatedDroppedRefundLinks = parsed.droppedRefundLinkCount > parsed.droppedRefundLinks.size,
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
                validRows = mutableListOf(),
                existingKeys = mutableSetOf(),
                categoryGroups = emptyMap(),
                sumIncome = BigDecimal.ZERO,
                sumExpense = BigDecimal.ZERO,
                dateFrom = null,
                dateTo = null,
                adjustedRows = emptyList(),
                adjustedCount = 0,
            )
        }

        val catalog = categoryService.listForHousehold(householdId).associateBy { it.code }
        val errors = mutableListOf<RowError>()
        val validRows = mutableListOf<ParsedRow>()
        var sumIncome = BigDecimal.ZERO
        var sumExpense = BigDecimal.ZERO
        var sumRefunds = BigDecimal.ZERO
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

            val refundFlag = raw["is_refund"].orEmpty().trim()
            val isRefund = when (refundFlag.lowercase()) {
                "", "false" -> false
                "true" -> true
                else -> {
                    rowErrors += RowError(rowNo, "IMPORT_REFUND_FLAG_INVALID", "is_refund", refundFlag)
                    false
                }
            }
            val refundOfKey = raw["refund_of_key"]?.trim()?.takeIf { it.isNotEmpty() }
            if (refundOfKey != null && !isRefund) {
                rowErrors += RowError(rowNo, "IMPORT_REFUND_LINK_INVALID", "refund_of_key", refundOfKey)
            }
            if (isRefund && direction != null && direction != Direction.expense) {
                rowErrors += RowError(rowNo, "IMPORT_REFUND_DIRECTION_INVALID", "direction", raw["direction"])
            }

            // Refunds are negative expenses and everything else is positive; zero is never a transaction.
            val amount = Csv.parseDecimal(raw["amount"].orEmpty())
            val amountSignOk = amount != null &&
                if (isRefund) amount.signum() < 0 else amount.signum() > 0
            if (!amountSignOk) {
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
                    isRefund = isRefund,
                    refundOfKey = refundOfKey,
                    originalKey = TransactionKeys.dedupKey(date, direction, categoryCode, normalizedAmount, description),
                )
                when (direction) {
                    Direction.income -> sumIncome += normalizedAmount
                    // A refund's negative amount nets the expense total, as it does everywhere else.
                    Direction.expense -> sumExpense += normalizedAmount
                }
                if (isRefund) sumRefunds += normalizedAmount
                if (minDate == null || date.isBefore(minDate)) minDate = date
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date
            } else {
                addErrors(errors, rowErrors)
            }
        }

        val existing = if (validRows.isNotEmpty()) {
            repository.findByHouseholdIdAndOccurrenceDateBetween(householdId, minDate!!, maxDate!!)
        } else emptyList()
        val existingKeys = existing.mapTo(mutableSetOf()) { TransactionKeys.dedupKey(it) }
        // Only non-refund expenses can be refunded, so only those are worth offering as link targets.
        val existingTargets = existing.filter { !it.isRefund && it.direction == Direction.expense }
            .associateBy({ TransactionKeys.dedupKey(it) }, { it.id })

        // Captured BEFORE the in-file duplicate resolver renames descriptions: a refund_of_key was written
        // against the keys as they appear in the file, and renaming is our own internal disambiguation.
        val inFileTargets = validRows.filter { !it.isRefund && it.direction == Direction.expense }
            .associateBy { it.originalKey }
        val refundTargets = resolveRefundTargets(householdId, validRows, existingTargets, inFileTargets)
        val dropped = validRows.filter { it.refundOfKey != null && !refundTargets.containsKey(it.refundOfKey) }
        val droppedRows = dropped.take(MAX_SKIPPED_REPORTED).map {
            SkippedRow(it.rowNumber, rowSummary(it.date, it.direction.name, it.categoryCode, it.amount, it.description))
        }

        val resolution = InFileDuplicateResolver.resolveAll(
            rows = validRows,
            existingKeys = existingKeys,
            keyOf = { row, desc -> TransactionKeys.dedupKey(row.date, row.direction, row.categoryCode, row.amount, desc) },
            descriptionOf = { it.description },
            rowNumberOf = { it.rowNumber },
            withDescription = { row, desc -> row.copy(description = desc) },
            summarize = { row -> rowSummary(row.date, row.direction.name, row.categoryCode, row.amount, row.description) },
        )

        val categoryGroups = catalog.mapValues { it.value.group ?: "ungrouped" }

        return ParsedFile(
            headers = parsed.headers,
            totalRows = parsed.rows.size,
            errors = errors,
            validRows = validRows,
            existingKeys = existingKeys,
            categoryGroups = categoryGroups,
            sumIncome = sumIncome,
            sumExpense = sumExpense,
            sumRefunds = sumRefunds,
            dateFrom = minDate,
            dateTo = maxDate,
            adjustedRows = resolution.adjustedRows,
            adjustedCount = resolution.adjustedCount,
            refundTargets = refundTargets,
            droppedRefundLinks = droppedRows,
            droppedRefundLinkCount = dropped.size,
        )
    }

    /** Which `refund_of_key`s can be honoured, and by what. A null value means "another row in this file",
     *  whose id only exists once it has been inserted. Keys absent from the result are dropped: the refund
     *  still imports, just unlinked — a missing original is not worth failing a whole file over. */
    private fun resolveRefundTargets(
        householdId: UUID,
        rows: List<ParsedRow>,
        existingTargets: Map<String, UUID>,
        inFileTargets: Map<String, ParsedRow>,
    ): Map<String, UUID?> {
        val wanted = rows.mapNotNull { it.refundOfKey }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val resolved = mutableMapOf<String, UUID?>()
        for (key in wanted) {
            // An existing row wins over a same-key row in the file: that row would be skipped as a
            // duplicate of it anyway, so the database id is the one that will still be there afterwards.
            existingTargets[key]?.let { resolved[key] = it; continue }
            if (inFileTargets.containsKey(key)) resolved[key] = null
        }
        // Originals outside the file's date window aren't in the set loaded for dedup; look those up by
        // the date their key carries, one day at a time (a file references few originals, if any).
        for (key in wanted - resolved.keys) {
            val date = TransactionKeys.dateOf(key) ?: continue
            val match = repository.findByHouseholdIdAndOccurrenceDateBetween(householdId, date, date)
                .filter { !it.isRefund && it.direction == Direction.expense && TransactionKeys.dedupKey(it) == key }
                .minByOrNull { it.createdAt }
            if (match != null) resolved[key] = match.id
        }
        return resolved
    }

    private fun validateHeaders(actual: List<String>): List<RowError> {
        // Either shape is accepted whole; the error names the closer of the two so the message is useful.
        val expected = if (actual.any { it == "is_refund" || it == "refund_of_key" }) refundHeaders else expectedHeaders
        val missing = expected - actual.toSet()
        val unknown = actual - expected.toSet()
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
        val isRefund: Boolean = false,
        val refundOfKey: String? = null,
        /** This row's key as written in the file. The in-file duplicate resolver may rewrite the
         *  description afterwards, but a `refund_of_key` was written against the original text. */
        val originalKey: String = "",
    )

    private data class ParsedFile(
        val headers: List<String>,
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: MutableList<ParsedRow>,
        val existingKeys: MutableSet<String>,
        val categoryGroups: Map<String, String>,
        val sumIncome: BigDecimal,
        val sumExpense: BigDecimal,
        val sumRefunds: BigDecimal = BigDecimal.ZERO,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
        val adjustedRows: List<AdjustedRow>,
        val adjustedCount: Int,
        /** Honourable `refund_of_key`s; a null value is a row in this same file. */
        val refundTargets: Map<String, UUID?> = emptyMap(),
        val droppedRefundLinks: List<SkippedRow> = emptyList(),
        val droppedRefundLinkCount: Int = 0,
    ) {
        fun toPreview(): PreviewSummary {
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (r in validRows) {
                if (existingKeys.contains(TransactionKeys.dedupKey(r.date, r.direction, r.categoryCode, r.amount, r.description))) {
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
                adjustedDescriptions = adjustedRows,
                adjustedCount = adjustedCount,
                truncatedAdjusted = adjustedCount > adjustedRows.size,
                sumIncome = sumIncome,
                sumExpense = sumExpense,
                sumRefunds = sumRefunds.takeIf { it.signum() != 0 },
                dateFrom = dateFrom?.toString(),
                dateTo = dateTo?.toString(),
                droppedRefundLinks = droppedRefundLinks,
                droppedRefundLinkCount = droppedRefundLinkCount,
                truncatedDroppedRefundLinks = droppedRefundLinkCount > droppedRefundLinks.size,
            )
        }
    }
}
