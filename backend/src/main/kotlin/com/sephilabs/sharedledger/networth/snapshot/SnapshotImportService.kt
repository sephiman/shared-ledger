package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.catalog.AssetClassAliases
import com.sephilabs.sharedledger.catalog.AssetClassRepository
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.CsvReader
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.import.*
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.*

enum class SnapshotDuplicatePolicy { skip, replace, abort }

@Service
class SnapshotImportService(
    private val snapshots: SnapshotRepository,
    private val liabilities: LiabilityRepository,
    private val assetClasses: AssetClassRepository,
    private val metrics: AppMetrics,
) {

    private val log = LoggerFactory.getLogger(SnapshotImportService::class.java)

    private val expectedHeaders = listOf("date", "note", "kind", "key", "value")

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream, policy: SnapshotDuplicatePolicy): PreviewSummary {
        val parsed = parseAndValidate(householdId, input)
        return parsed.toPreview(policy)
    }

    @Transactional
    fun execute(
        householdId: UUID,
        input: InputStream,
        importer: User,
        policy: SnapshotDuplicatePolicy,
    ): ExecuteResult {
        log.info("Snapshot import started: household={} importer={} policy={}", householdId, importer.id, policy)
        val parsed = parseAndValidate(householdId, input)
        if (parsed.errors.isNotEmpty()) {
            log.warn("Snapshot import aborted: {} validation errors", parsed.errors.size)
            throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        }
        var inserted = 0
        var skipped = 0
        var replaced = 0
        val skippedList = mutableListOf<SkippedRow>()
        for (group in parsed.groups) {
            val existing = parsed.existingByDate[group.date]
            if (existing != null) {
                when (policy) {
                    SnapshotDuplicatePolicy.skip -> {
                        skipped++
                        log.info("Snapshot import skipped date {}: existing snapshot kept", group.date)
                        if (skippedList.size < MAX_SKIPPED_REPORTED) {
                            skippedList.add(SkippedRow(0, "${group.date}${if (!group.note.isNullOrEmpty()) " · ${group.note}" else ""}"))
                        }
                        continue
                    }
                    SnapshotDuplicatePolicy.abort -> {
                        log.warn("Snapshot import aborted: date collision on {}", group.date)
                        throw AppException.badRequest("IMPORT_SNAPSHOT_DATE_COLLISION")
                    }
                    SnapshotDuplicatePolicy.replace -> {
                        log.info("Snapshot import replacing existing snapshot on {}", group.date)
                        snapshots.delete(existing)
                        snapshots.flush()
                        replaced++
                    }
                }
            } else {
                inserted++
            }
            val snapshot = Snapshot(
                householdId = householdId,
                snapshotDate = group.date,
                note = group.note,
                createdByUserId = importer.id,
                updatedByUserId = importer.id,
            )
            snapshot.assetValues = group.assetValues.map { (code, value) ->
                SnapshotAssetValue(
                    id = SnapshotAssetValueId(snapshot.id, code),
                    value = Money.normalize(value),
                )
            }.toMutableList()
            snapshot.liabilityBalances = group.liabilityBalances.map { (liabilityId, balance) ->
                SnapshotLiabilityBalance(
                    id = SnapshotLiabilityBalanceId(snapshot.id, liabilityId),
                    balance = Money.normalize(balance),
                )
            }.toMutableList()
            snapshots.save(snapshot)
            metrics.snapshotCreated()
        }
        log.info("Snapshot import finished: inserted={} skipped={} replaced={} groups={}", inserted, skipped, replaced, parsed.groups.size)
        return ExecuteResult(
            inserted = inserted,
            skipped = skipped,
            replaced = replaced,
            skippedRows = skippedList,
            truncatedSkipped = skipped > skippedList.size,
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
                groups = emptyList(),
                existingByDate = emptyMap(),
                sumAssets = BigDecimal.ZERO,
                sumLiabilities = BigDecimal.ZERO,
                dateFrom = null,
                dateTo = null,
            )
        }

        val validAssetClasses = assetClasses.findAllByOrderBySortOrderAsc().map { it.code }.toSet()
        val allLiabilities = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)
        val activeLiabilitiesByName = allLiabilities.filter { it.active }.associateBy { it.name }
        val anyLiabilityByName = allLiabilities.associateBy { it.name }
        val activeLiabilityIds = activeLiabilitiesByName.values.map { it.id }.toSet()

        val errors = mutableListOf<RowError>()
        val perDate: MutableMap<LocalDate, RowGroupBuilder> = linkedMapOf()
        var sumAssets = BigDecimal.ZERO
        var sumLiabilities = BigDecimal.ZERO

        for ((idx, raw) in parsed.rows.withIndex()) {
            val rowNo = idx + 2
            val rowErrors = mutableListOf<RowError>()

            val date = parseDate(raw["date"]) ?: run {
                rowErrors += RowError(rowNo, "IMPORT_DATE_INVALID", "date", raw["date"])
                null
            }
            val note = raw["note"]?.takeIf { it.isNotEmpty() }
            val kind = raw["kind"]?.lowercase().orEmpty()
            if (kind != "asset" && kind != "liability") {
                rowErrors += RowError(rowNo, "IMPORT_KIND_INVALID", "kind", raw["kind"])
            }
            val key = raw["key"].orEmpty().let { if (kind == "asset") AssetClassAliases.canonical(it) else it }
            val value = Csv.parseDecimal(raw["value"].orEmpty())
            if (value == null || value < BigDecimal.ZERO) {
                rowErrors += RowError(rowNo, "IMPORT_VALUE_INVALID", "value", raw["value"])
            }

            var liabilityId: UUID? = null
            if (rowErrors.isEmpty()) {
                if (kind == "asset" && !validAssetClasses.contains(key)) {
                    rowErrors += RowError(rowNo, "IMPORT_ASSET_CLASS_UNKNOWN", "key", key)
                } else if (kind == "liability") {
                    val active = activeLiabilitiesByName[key]
                    if (active != null) {
                        liabilityId = active.id
                    } else if (anyLiabilityByName.containsKey(key)) {
                        rowErrors += RowError(rowNo, "IMPORT_LIABILITY_INACTIVE", "key", key)
                    } else {
                        rowErrors += RowError(rowNo, "IMPORT_LIABILITY_UNKNOWN", "key", key)
                    }
                }
            }

            if (rowErrors.isNotEmpty()) {
                addErrors(errors, rowErrors)
                continue
            }

            val builder = perDate.getOrPut(date!!) { RowGroupBuilder(date, note, mutableListOf()) }
            if (builder.note != note) {
                addErrors(errors, listOf(RowError(rowNo, "IMPORT_SNAPSHOT_NOTE_MISMATCH", "note", note ?: "")))
                continue
            }
            val seen = builder.entries.any { it.kind == kind && it.lookupKey == key }
            if (seen) {
                addErrors(errors, listOf(RowError(rowNo, "IMPORT_SNAPSHOT_DUPLICATE_KEY", "key", "$kind:$key")))
                continue
            }
            builder.entries.add(GroupEntry(kind = kind, lookupKey = key, liabilityId = liabilityId, value = value!!))
            if (kind == "asset") sumAssets += value else sumLiabilities += value
        }

        // Per-group completeness checks
        val groups = mutableListOf<RowGroup>()
        for (builder in perDate.values) {
            val assetCodes = builder.entries.filter { it.kind == "asset" }.map { it.lookupKey }.toSet()
            val missingClasses = validAssetClasses - assetCodes
            if (missingClasses.isNotEmpty()) {
                addErrors(errors, listOf(RowError(
                    builder.firstRowNumber(),
                    "IMPORT_SNAPSHOT_MISSING_ASSET_VALUES",
                    "date",
                    builder.date.toString()
                )))
                continue
            }
            val providedLiabilityIds = builder.entries.filter { it.kind == "liability" }.mapNotNull { it.liabilityId }.toSet()
            val missingLiabilities = activeLiabilityIds - providedLiabilityIds
            if (missingLiabilities.isNotEmpty()) {
                addErrors(errors, listOf(RowError(
                    builder.firstRowNumber(),
                    "IMPORT_SNAPSHOT_MISSING_LIABILITIES",
                    "date",
                    builder.date.toString()
                )))
                continue
            }
            val assetValues = builder.entries.filter { it.kind == "asset" }
                .associate { it.lookupKey to it.value }
            val liabilityBalances: Map<UUID, BigDecimal> = builder.entries.filter { it.kind == "liability" }
                .associate { it.liabilityId!! to it.value }
            groups += RowGroup(builder.date, builder.note, assetValues, liabilityBalances)
        }

        val dates = groups.map { it.date }
        val from = dates.minOrNull()
        val to = dates.maxOrNull()
        val existingByDate = if (groups.isNotEmpty()) {
            snapshots.findInRange(householdId, from!!, to!!).associateBy { it.snapshotDate }
        } else emptyMap()

        return ParsedFile(
            totalRows = parsed.rows.size,
            errors = errors,
            groups = groups,
            existingByDate = existingByDate,
            sumAssets = sumAssets,
            sumLiabilities = sumLiabilities,
            dateFrom = from,
            dateTo = to,
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

    private fun addErrors(sink: MutableList<RowError>, more: List<RowError>) {
        for (e in more) {
            if (sink.size >= MAX_ERRORS_REPORTED) return
            sink.add(e)
        }
    }

    private data class GroupEntry(
        val kind: String,
        val lookupKey: String,
        val liabilityId: UUID?,
        val value: BigDecimal,
    )

    private data class RowGroupBuilder(
        val date: LocalDate,
        val note: String?,
        val entries: MutableList<GroupEntry>,
    ) {
        fun firstRowNumber(): Int = 2 // best-effort; we don't track per-group rows for the missing-completeness error
    }

    private data class RowGroup(
        val date: LocalDate,
        val note: String?,
        val assetValues: Map<String, BigDecimal>,
        val liabilityBalances: Map<UUID, BigDecimal>,
    )

    private data class ParsedFile(
        val totalRows: Int,
        val errors: List<RowError>,
        val groups: List<RowGroup>,
        val existingByDate: Map<LocalDate, Snapshot>,
        val sumAssets: BigDecimal,
        val sumLiabilities: BigDecimal,
        val dateFrom: LocalDate?,
        val dateTo: LocalDate?,
    ) {
        fun toPreview(policy: SnapshotDuplicatePolicy): PreviewSummary {
            val collisions = groups.filter { existingByDate.containsKey(it.date) }
            val wouldReplace = if (policy == SnapshotDuplicatePolicy.replace) collisions.size else 0
            val wouldSkip = if (policy == SnapshotDuplicatePolicy.skip) collisions.size else 0
            val wouldInsert = groups.size - collisions.size
            val skippedList = if (policy == SnapshotDuplicatePolicy.skip) {
                collisions.take(MAX_SKIPPED_REPORTED).map {
                    SkippedRow(0, "${it.date}${if (!it.note.isNullOrEmpty()) " · ${it.note}" else ""}")
                }
            } else emptyList()
            return PreviewSummary(
                totalRows = totalRows,
                wouldInsert = wouldInsert,
                wouldSkip = wouldSkip,
                wouldReplace = wouldReplace,
                errorCount = errors.size,
                errors = errors,
                truncatedErrors = errors.size >= MAX_ERRORS_REPORTED,
                skippedRows = skippedList,
                truncatedSkipped = wouldSkip > skippedList.size,
                sumAssets = sumAssets,
                sumLiabilities = sumLiabilities,
                dateFrom = dateFrom?.toString(),
                dateTo = dateTo?.toString(),
            )
        }
    }
}
