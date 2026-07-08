package com.sephilabs.sharedledger.networth.csv

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.CsvReader
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.import.ExecuteResult
import com.sephilabs.sharedledger.common.import.MAX_ERRORS_REPORTED
import com.sephilabs.sharedledger.common.import.PreviewSummary
import com.sephilabs.sharedledger.common.import.RowError
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.asset.Asset
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.asset.AssetType
import com.sephilabs.sharedledger.networth.asset.AssetValueEntry
import com.sephilabs.sharedledger.networth.asset.AssetValueEntryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Import for named assets and their value history. Validate-then-confirm; re-importing an export is
 * idempotent: an asset is matched by name (reused, not duplicated) and a value entry is skipped when
 * one with the same date and value already exists. Never notifies (imports stay silent).
 */
@Service
class AssetImportService(
    private val assets: AssetRepository,
    private val values: AssetValueEntryRepository,
) {
    private val expectedHeaders = listOf("name", "type", "active", "value_date", "value")

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary {
        val parsed = parse(householdId, input)
        return PreviewSummary(
            totalRows = parsed.totalRows,
            wouldInsert = parsed.toInsert.size,
            wouldSkip = parsed.toSkip,
            wouldReplace = 0,
            errorCount = parsed.errors.size,
            errors = parsed.errors,
            truncatedErrors = parsed.errors.size >= MAX_ERRORS_REPORTED,
        )
    }

    @Transactional
    fun execute(householdId: UUID, input: InputStream, by: User): ExecuteResult {
        val parsed = parse(householdId, input)
        if (parsed.errors.isNotEmpty()) throw AppException.badRequest("IMPORT_VALIDATION_FAILED")
        val existingByName = assets.findAllByHouseholdIdOrderByNameAsc(householdId).associateBy { it.name }.toMutableMap()
        var inserted = 0
        for (row in parsed.toInsert) {
            val asset = existingByName[row.name] ?: Asset(
                householdId = householdId,
                name = row.name,
                type = row.type,
                active = row.active,
                createdByUserId = by.id,
                updatedByUserId = by.id,
            ).also { assets.save(it); existingByName[row.name] = it; inserted++ }
            asset.type = row.type
            asset.active = row.active
            asset.updatedByUserId = by.id
            if (row.valueDate != null && row.value != null) {
                val dup = values.findAllByAssetIdOrderByValueDateDescCreatedAtDesc(asset.id)
                    .any { it.valueDate == row.valueDate && it.value.compareTo(row.value) == 0 }
                if (!dup) {
                    values.save(AssetValueEntry(assetId = asset.id, valueDate = row.valueDate, value = row.value, createdByUserId = by.id, updatedByUserId = by.id))
                    inserted++
                }
            }
        }
        return ExecuteResult(inserted = inserted, skipped = parsed.toSkip, replaced = 0)
    }

    private fun parse(householdId: UUID, input: InputStream): Parsed {
        val result = CsvReader.parse(input)
        if (result.parseError != null) throw AppException.badRequest("IMPORT_PARSE_FAILED")
        CsvSupport.headerErrorArgs(expectedHeaders, result.headers)?.let {
            return Parsed(result.rows.size, emptyList(), 0, listOf(RowError(1, "IMPORT_HEADER_INVALID", null, it)))
        }
        val existing = assets.findAllByHouseholdIdOrderByNameAsc(householdId)
        val existingValues = existing.associate { a ->
            a.name to values.findAllByAssetIdOrderByValueDateDescCreatedAtDesc(a.id).map { it.valueDate to it.value }.toSet()
        }
        val errors = mutableListOf<RowError>()
        val toInsert = mutableListOf<AssetRow>()
        var toSkip = 0
        for ((idx, raw) in result.rows.withIndex()) {
            val rowNo = idx + 2
            val name = raw["name"].orEmpty().trim()
            if (name.isEmpty()) { errors.add(RowError(rowNo, "IMPORT_NAME_REQUIRED", "name", "")); continue }
            val type = when (raw["type"].orEmpty().trim().lowercase()) {
                "", "other" -> AssetType.other
                "property" -> AssetType.property
                "vehicle" -> AssetType.vehicle
                else -> { errors.add(RowError(rowNo, "IMPORT_ASSET_TYPE_INVALID", "type", raw["type"])); continue }
            }
            val active = CsvSupport.parseBoolean(raw["active"])
            if (active == null) { errors.add(RowError(rowNo, "IMPORT_BOOLEAN_INVALID", "active", raw["active"])); continue }
            val dateStr = raw["value_date"].orEmpty().trim()
            val valStr = raw["value"].orEmpty().trim()
            var valueDate: LocalDate? = null
            var value: BigDecimal? = null
            if (dateStr.isNotEmpty() || valStr.isNotEmpty()) {
                valueDate = parseDate(dateStr) ?: run { errors.add(RowError(rowNo, "IMPORT_DATE_INVALID", "value_date", dateStr)); continue }
                value = Csv.parseDecimal(valStr)?.let { Money.normalize(it) } ?: run { errors.add(RowError(rowNo, "IMPORT_VALUE_INVALID", "value", valStr)); continue }
            }
            if (valueDate != null && (valueDate to value!!) in (existingValues[name] ?: emptySet())) { toSkip++; continue }
            toInsert.add(AssetRow(name, type, active, valueDate, value))
        }
        return Parsed(result.rows.size, toInsert, toSkip, errors)
    }

    private fun parseDate(s: String): LocalDate? =
        if (s.isEmpty()) null else try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }

    private data class AssetRow(val name: String, val type: AssetType, val active: Boolean, val valueDate: LocalDate?, val value: BigDecimal?)
    private data class Parsed(val totalRows: Int, val toInsert: List<AssetRow>, val toSkip: Int, val errors: List<RowError>)
}
