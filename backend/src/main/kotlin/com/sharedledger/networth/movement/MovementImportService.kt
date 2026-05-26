package com.sharedledger.networth.movement

import com.sharedledger.catalog.AssetClassRepository
import com.sharedledger.common.Csv
import com.sharedledger.common.CsvReader
import com.sharedledger.common.Money
import com.sharedledger.common.errors.AppException
import com.sharedledger.common.import.AdjustedRow
import com.sharedledger.common.import.ExecuteResult
import com.sharedledger.common.import.InFileDuplicateResolver
import com.sharedledger.common.import.MAX_ERRORS_REPORTED
import com.sharedledger.common.import.MAX_SKIPPED_REPORTED
import com.sharedledger.common.import.PreviewSummary
import com.sharedledger.common.import.RowError
import com.sharedledger.common.import.SkippedRow
import com.sharedledger.identity.user.User
import com.sharedledger.networth.liability.LiabilityRepository
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
class MovementImportService(
    private val movements: MovementRepository,
    private val assetClasses: AssetClassRepository,
    private val liabilities: LiabilityRepository,
    private val metrics: AppMetrics,
) {

    @PersistenceContext
    private lateinit var em: EntityManager

    private val log = LoggerFactory.getLogger(MovementImportService::class.java)

    private val expectedHeaders = listOf(
        "date", "type", "asset_class_code", "liability_name", "amount", "description", "created_at"
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary {
        return parseAndValidate(householdId, input).toPreview()
    }

    @Transactional
    fun execute(householdId: UUID, input: InputStream, importer: User): ExecuteResult {
        log.info("Movement import started: household={} importer={}", householdId, importer.id)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            log.warn("Movement import aborted: {} validation errors", parsed.errors.size)
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (row in parsed.validRows) {
            val key = dedupKey(row.date, row.type, row.assetClassCode, row.liabilityId, row.amount, row.description)
            if (parsed.existingKeys.contains(key)) {
                val summary = rowSummary(row.date, row.type.name, row.assetClassCode, row.liabilityId, row.amount, row.description)
                log.info("Movement import skipped row {}: duplicate — {}", row.rowNumber, summary)
                if (skippedList.size < MAX_SKIPPED_REPORTED) skippedList.add(SkippedRow(row.rowNumber, summary))
                continue
            }
            val movement = NetWorthMovement(
                householdId = householdId,
                movementDate = row.date,
                type = row.type,
                assetClassCode = row.assetClassCode,
                liabilityId = row.liabilityId,
                amount = row.amount,
                description = row.description,
                createdByUserId = importer.id,
                updatedByUserId = importer.id,
            )
            movements.save(movement)
            if (row.createdAt != null) {
                em.flush()
                em.createQuery("UPDATE NetWorthMovement m SET m.createdAt = :ca WHERE m.id = :id")
                    .setParameter("ca", row.createdAt)
                    .setParameter("id", movement.id)
                    .executeUpdate()
            }
            parsed.existingKeys.add(key)
            metrics.movementCreated(
                type = row.type.name,
                targetClass = row.assetClassCode,
                targetLiabilityId = row.liabilityId?.toString(),
            )
            inserted++
        }
        val totalSkipped = parsed.validRows.size - inserted
        log.info("Movement import finished: inserted={} skipped={} totalRows={}", inserted, totalSkipped, parsed.totalRows)
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
                sumContributions = BigDecimal.ZERO,
                sumWithdrawals = BigDecimal.ZERO,
                sumDebtPayments = BigDecimal.ZERO,
                dateFrom = null,
                dateTo = null,
                adjustedRows = emptyList(),
                adjustedCount = 0,
            )
        }

        val validAssetCodes = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }.toSet()
        val liabilitiesByName = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)
            .associateBy { it.name }

        val errors = mutableListOf<RowError>()
        val validRows = mutableListOf<ParsedRow>()
        var sumContributions = BigDecimal.ZERO
        var sumWithdrawals = BigDecimal.ZERO
        var sumDebtPayments = BigDecimal.ZERO
        var minDate: LocalDate? = null
        var maxDate: LocalDate? = null

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val date = parseDate(raw["date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "date", raw["date"])
                null
            }
            if (date != null && date.isAfter(LocalDate.now().plusYears(1))) {
                rowErrors += RowError(rowNo, "IMPORT_DATE_FAR_FUTURE", "date", raw["date"])
            }

            val typeStr = raw["type"]?.lowercase().orEmpty()
            val type = runCatching { MovementType.valueOf(typeStr) }.getOrNull()
            if (type == null) {
                rowErrors += RowError(rowNo, "IMPORT_MOVEMENT_TYPE_INVALID", "type", raw["type"])
            }

            val assetCode = raw["asset_class_code"]?.takeIf { it.isNotEmpty() }
            val liabilityName = raw["liability_name"]?.takeIf { it.isNotEmpty() }
            var liabilityId: UUID? = null

            if (type != null) {
                when (type) {
                    MovementType.contribution, MovementType.withdrawal -> {
                        if (assetCode == null) {
                            rowErrors += RowError(rowNo, "IMPORT_MOVEMENT_ASSET_REQUIRED", "asset_class_code", null)
                        } else if (!validAssetCodes.contains(assetCode)) {
                            rowErrors += RowError(rowNo, "IMPORT_ASSET_CLASS_UNKNOWN", "asset_class_code", assetCode)
                        }
                        if (liabilityName != null) {
                            rowErrors += RowError(rowNo, "IMPORT_MOVEMENT_TARGET_MISMATCH", "liability_name", liabilityName)
                        }
                    }
                    MovementType.debt_payment -> {
                        if (liabilityName == null) {
                            rowErrors += RowError(rowNo, "IMPORT_MOVEMENT_LIABILITY_REQUIRED", "liability_name", null)
                        } else {
                            val found = liabilitiesByName[liabilityName]
                            if (found == null) {
                                rowErrors += RowError(rowNo, "IMPORT_LIABILITY_UNKNOWN", "liability_name", liabilityName)
                            } else {
                                liabilityId = found.id
                            }
                        }
                        if (assetCode != null) {
                            rowErrors += RowError(rowNo, "IMPORT_MOVEMENT_TARGET_MISMATCH", "asset_class_code", assetCode)
                        }
                    }
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

            val createdAt = raw["created_at"].orEmpty().let { if (it.isEmpty()) null else parseInstant(it) }
            if (raw["created_at"].orEmpty().isNotEmpty() && createdAt == null) {
                rowErrors += RowError(rowNo, "IMPORT_TIMESTAMP_INVALID", "created_at", raw["created_at"])
            }

            if (rowErrors.isEmpty()) {
                val normalized = Money.normalize(amount!!)
                val finalAssetCode = if (type == MovementType.debt_payment) null else assetCode
                validRows += ParsedRow(
                    rowNumber = rowNo,
                    date = date!!,
                    type = type!!,
                    assetClassCode = finalAssetCode,
                    liabilityId = liabilityId,
                    amount = normalized,
                    description = description,
                    createdAt = createdAt,
                )
                when (type) {
                    MovementType.contribution -> sumContributions += normalized
                    MovementType.withdrawal -> sumWithdrawals += normalized
                    MovementType.debt_payment -> sumDebtPayments += normalized
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
            movements.findInRange(householdId, from, to)
                .mapTo(mutableSetOf()) {
                    dedupKey(it.movementDate, it.type, it.assetClassCode, it.liabilityId, it.amount, it.description)
                }
        } else mutableSetOf()

        val resolution = InFileDuplicateResolver.resolveAll(
            rows = validRows,
            existingKeys = existingKeys,
            keyOf = { row, desc -> dedupKey(row.date, row.type, row.assetClassCode, row.liabilityId, row.amount, desc) },
            descriptionOf = { it.description },
            rowNumberOf = { it.rowNumber },
            withDescription = { row, desc -> row.copy(description = desc) },
            summarize = { row -> rowSummary(row.date, row.type.name, row.assetClassCode, row.liabilityId, row.amount, row.description) },
        )

        return ParsedFile(
            totalRows = parsed.rows.size,
            errors = errors,
            validRows = validRows,
            existingKeys = existingKeys,
            sumContributions = sumContributions,
            sumWithdrawals = sumWithdrawals,
            sumDebtPayments = sumDebtPayments,
            dateFrom = minDate,
            dateTo = maxDate,
            adjustedRows = resolution.adjustedRows,
            adjustedCount = resolution.adjustedCount,
        )
    }

    private fun rowSummary(
        date: LocalDate,
        type: String,
        assetClassCode: String?,
        liabilityId: UUID?,
        amount: BigDecimal,
        description: String?,
    ): String {
        val target = assetClassCode ?: liabilityId?.toString() ?: "—"
        val desc = description?.take(40) ?: ""
        return "$date · $type · $target · ${Money.normalize(amount).toPlainString()}${if (desc.isNotEmpty()) " · $desc" else ""}"
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

    private fun parseInstant(value: String): Instant? =
        try { Instant.parse(value) } catch (_: DateTimeParseException) { null }

    private fun dedupKey(
        date: LocalDate,
        type: MovementType,
        assetClassCode: String?,
        liabilityId: UUID?,
        amount: BigDecimal,
        description: String?,
    ): String {
        val desc = description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        val target = assetClassCode ?: liabilityId?.toString() ?: ""
        return "${date}|${type.name}|$target|${Money.normalize(amount).toPlainString()}|$desc"
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
        val type: MovementType,
        val assetClassCode: String?,
        val liabilityId: UUID?,
        val amount: BigDecimal,
        val description: String?,
        val createdAt: Instant?,
    )

    private data class ParsedFile(
        val totalRows: Int,
        val errors: List<RowError>,
        val validRows: MutableList<ParsedRow>,
        val existingKeys: MutableSet<String>,
        val sumContributions: BigDecimal,
        val sumWithdrawals: BigDecimal,
        val sumDebtPayments: BigDecimal,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
        val adjustedRows: List<AdjustedRow>,
        val adjustedCount: Int,
    ) {
        fun toPreview(): PreviewSummary {
            val skippedList = mutableListOf<SkippedRow>()
            var wouldSkip = 0
            for (row in validRows) {
                if (existingKeys.contains(buildKey(row))) {
                    wouldSkip++
                    if (skippedList.size < MAX_SKIPPED_REPORTED) {
                        val target = row.assetClassCode ?: row.liabilityId?.toString() ?: "—"
                        val desc = row.description?.take(40) ?: ""
                        val summary = "${row.date} · ${row.type.name} · $target · " +
                            "${Money.normalize(row.amount).toPlainString()}${if (desc.isNotEmpty()) " · $desc" else ""}"
                        skippedList.add(SkippedRow(row.rowNumber, summary))
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
                sumContributions = sumContributions,
                sumWithdrawals = sumWithdrawals,
                sumDebtPayments = sumDebtPayments,
                dateFrom = dateFrom?.toString(),
                dateTo = dateTo?.toString(),
            )
        }

        private fun buildKey(row: ParsedRow): String {
            val desc = row.description?.trim()?.takeIf { it.isNotEmpty() } ?: ""
            val target = row.assetClassCode ?: row.liabilityId?.toString() ?: ""
            return "${row.date}|${row.type.name}|$target|${Money.normalize(row.amount).toPlainString()}|$desc"
        }
    }
}
