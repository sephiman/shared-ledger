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
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntry
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntryRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Import for named liabilities and their manual balance history. Idempotent: matched by name, a balance
 *  entry skipped when same date and value exist. Amortization inputs import separately. */
@Service
class LiabilityImportService(
    private val liabilities: LiabilityRepository,
    private val balances: LiabilityBalanceEntryRepository,
) {
    private val expectedHeaders = listOf("name", "active", "amortizable", "charge_day", "balance_date", "balance")

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
        val existingByName = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId).associateBy { it.name }.toMutableMap()
        var inserted = 0
        for (row in parsed.toInsert) {
            val liability = existingByName[row.name] ?: Liability(
                householdId = householdId,
                name = row.name,
                active = row.active,
                amortizable = row.amortizable,
                chargeDay = row.chargeDay,
                createdByUserId = by.id,
                updatedByUserId = by.id,
            ).also { liabilities.save(it); existingByName[row.name] = it; inserted++ }
            liability.active = row.active
            liability.amortizable = row.amortizable
            liability.chargeDay = row.chargeDay
            liability.updatedByUserId = by.id
            if (row.balanceDate != null && row.balance != null) {
                val dup = balances.findAllByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(liability.id)
                    .any { it.balanceDate == row.balanceDate && it.balance.compareTo(row.balance) == 0 }
                if (!dup) {
                    balances.save(LiabilityBalanceEntry(liabilityId = liability.id, balanceDate = row.balanceDate, balance = row.balance, createdByUserId = by.id, updatedByUserId = by.id))
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
        val existing = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)
        val existingBalances = existing.associate { l ->
            l.name to balances.findAllByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(l.id).map { it.balanceDate to it.balance }.toSet()
        }
        val errors = mutableListOf<RowError>()
        val toInsert = mutableListOf<Row>()
        var toSkip = 0
        for ((idx, raw) in result.rows.withIndex()) {
            val rowNo = idx + 2
            val name = raw["name"].orEmpty().trim()
            if (name.isEmpty()) { errors.add(RowError(rowNo, "IMPORT_NAME_REQUIRED", "name", "")); continue }
            val active = CsvSupport.parseBoolean(raw["active"])
            if (active == null) { errors.add(RowError(rowNo, "IMPORT_BOOLEAN_INVALID", "active", raw["active"])); continue }
            val amortizable = CsvSupport.parseBoolean(raw["amortizable"])
            if (amortizable == null) { errors.add(RowError(rowNo, "IMPORT_BOOLEAN_INVALID", "amortizable", raw["amortizable"])); continue }
            val chargeDayStr = raw["charge_day"].orEmpty().trim()
            val chargeDay = if (chargeDayStr.isEmpty()) null else chargeDayStr.toIntOrNull()?.takeIf { it in 1..31 }
                ?: run { errors.add(RowError(rowNo, "IMPORT_CHARGE_DAY_INVALID", "charge_day", chargeDayStr)); continue }
            val dateStr = raw["balance_date"].orEmpty().trim()
            val balStr = raw["balance"].orEmpty().trim()
            var balanceDate: LocalDate? = null
            var balance: BigDecimal? = null
            if (dateStr.isNotEmpty() || balStr.isNotEmpty()) {
                balanceDate = parseDate(dateStr) ?: run { errors.add(RowError(rowNo, "IMPORT_DATE_INVALID", "balance_date", dateStr)); continue }
                balance = Csv.parseDecimal(balStr)?.let { Money.normalize(it) } ?: run { errors.add(RowError(rowNo, "IMPORT_VALUE_INVALID", "balance", balStr)); continue }
            }
            if (balanceDate != null && (balanceDate to balance!!) in (existingBalances[name] ?: emptySet())) { toSkip++; continue }
            toInsert.add(Row(name, active, amortizable, chargeDay, balanceDate, balance))
        }
        return Parsed(result.rows.size, toInsert, toSkip, errors)
    }

    private fun parseDate(s: String): LocalDate? =
        if (s.isEmpty()) null else try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }

    private data class Row(val name: String, val active: Boolean, val amortizable: Boolean, val chargeDay: Int?, val balanceDate: LocalDate?, val balance: BigDecimal?)
    private data class Parsed(val totalRows: Int, val toInsert: List<Row>, val toSkip: Int, val errors: List<RowError>)
}
