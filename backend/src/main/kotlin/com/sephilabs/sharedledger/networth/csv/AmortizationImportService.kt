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
import com.sephilabs.sharedledger.networth.amortization.AmortizationEntry
import com.sephilabs.sharedledger.networth.amortization.AmortizationEntryRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationMethod
import com.sephilabs.sharedledger.networth.amortization.AmortizationPart
import com.sephilabs.sharedledger.networth.amortization.AmortizationPartRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationPrepayment
import com.sephilabs.sharedledger.networth.amortization.AmortizationPrepaymentRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationRateRevision
import com.sephilabs.sharedledger.networth.amortization.AmortizationRateRevisionRepository
import com.sephilabs.sharedledger.networth.amortization.PrepaymentMode
import com.sephilabs.sharedledger.networth.amortization.StartMode
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Import for amortization inputs (parts / revisions / prepayments) AND generated instalments, from the
 *  discriminated `record_type` file; the liability must already exist. Idempotent: parts match by
 *  (liability, start_date, principal, method), the rest dedup on their natural keys. */
@Service
class AmortizationImportService(
    private val liabilities: LiabilityRepository,
    private val parts: AmortizationPartRepository,
    private val revisions: AmortizationRateRevisionRepository,
    private val prepayments: AmortizationPrepaymentRepository,
    private val entries: AmortizationEntryRepository,
) {
    private val expectedHeaders = listOf(
        "liability_name", "part_label", "record_type", "date", "method",
        "principal", "annual_rate", "term_months", "instalment", "amount", "mode", "interest", "resulting_balance",
        "start_mode", "anchor_date", "anchor_balance",
    )

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, input: InputStream): PreviewSummary {
        val parsed = parse(householdId, input)
        return PreviewSummary(
            totalRows = parsed.total,
            wouldInsert = parsed.rows.size,
            wouldSkip = 0,
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
        val liabilityByName = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId).associateBy { it.name }
        // (liabilityName, partLabel) -> partId, resolved as part rows are processed.
        val partIds = mutableMapOf<Pair<String, String>, UUID>()
        var inserted = 0
        var skipped = 0

        // Pass 1: parts (so children can resolve their part).
        for (row in parsed.rows.filter { it.recordType == "part" }) {
            val liability = liabilityByName[row.liabilityName]!!
            val existing = parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liability.id).firstOrNull {
                it.startDate == row.date && it.originalPrincipal.compareTo(row.principal) == 0 && it.method == row.method
            }
            val part = existing ?: AmortizationPart(
                liabilityId = liability.id,
                label = row.partLabel.takeUnless { it.startsWith("#") },
                method = row.method!!,
                startMode = row.startMode,
                originalPrincipal = row.principal!!,
                annualRate = row.annualRate ?: BigDecimal.ZERO,
                termMonths = row.termMonths,
                instalment = row.instalment,
                startDate = row.date!!,
                anchorDate = row.anchorDate,
                anchorBalance = row.anchorBalance,
                createdByUserId = by.id,
                updatedByUserId = by.id,
            ).also { parts.save(it); inserted++ }
            partIds[row.liabilityName to row.partLabel] = part.id
            if (existing != null) skipped++
        }

        // Pass 2: children.
        for (row in parsed.rows.filter { it.recordType != "part" }) {
            val partId = partIds[row.liabilityName to row.partLabel]
                ?: parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liabilityByName[row.liabilityName]!!.id)
                    .firstOrNull { AmortizationCsvLabels.matches(it.label, row.partLabel) }?.id
                ?: continue
            when (row.recordType) {
                "revision" -> {
                    val dup = revisions.findAllByPartIdOrderByEffectiveDateAsc(partId).any { it.effectiveDate == row.date }
                    if (dup) { skipped++ } else {
                        revisions.save(AmortizationRateRevision(partId = partId, effectiveDate = row.date!!, annualRate = row.annualRate!!, createdByUserId = by.id, updatedByUserId = by.id))
                        inserted++
                    }
                }
                "prepayment" -> {
                    val dup = prepayments.findAllByPartIdOrderByPrepaymentDateAsc(partId).any { it.prepaymentDate == row.date && it.amount.compareTo(row.amount) == 0 }
                    if (dup) { skipped++ } else {
                        prepayments.save(AmortizationPrepayment(partId = partId, prepaymentDate = row.date!!, amount = row.amount!!, mode = row.mode!!, createdByUserId = by.id, updatedByUserId = by.id))
                        inserted++
                    }
                }
                "entry" -> {
                    if (entries.existsByPartIdAndChargeDate(partId, row.date!!)) { skipped++ } else {
                        val zero = Money.normalize(BigDecimal.ZERO)
                        entries.save(AmortizationEntry(partId = partId, chargeDate = row.date, interest = row.interest ?: zero, principal = row.principal ?: zero, resultingBalance = row.resultingBalance ?: zero))
                        inserted++
                    }
                }
            }
        }
        return ExecuteResult(inserted = inserted, skipped = skipped, replaced = 0)
    }

    private fun parse(householdId: UUID, input: InputStream): Parsed {
        val result = CsvReader.parse(input)
        if (result.parseError != null) throw AppException.badRequest("IMPORT_PARSE_FAILED")
        CsvSupport.headerErrorArgs(expectedHeaders, result.headers)?.let {
            return Parsed(result.rows.size, emptyList(), listOf(RowError(1, "IMPORT_HEADER_INVALID", null, it)))
        }
        val knownNames = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId).map { it.name }.toSet()
        val errors = mutableListOf<RowError>()
        val rows = mutableListOf<Row>()
        for ((idx, raw) in result.rows.withIndex()) {
            val rowNo = idx + 2
            val name = raw["liability_name"].orEmpty().trim()
            val label = raw["part_label"].orEmpty().trim().ifEmpty { "#1" }
            val type = raw["record_type"].orEmpty().trim().lowercase()
            if (name.isEmpty() || name !in knownNames) { errors.add(RowError(rowNo, "IMPORT_LIABILITY_UNKNOWN", "liability_name", name)); continue }
            if (type !in setOf("part", "revision", "prepayment", "entry")) { errors.add(RowError(rowNo, "IMPORT_RECORD_TYPE_INVALID", "record_type", type)); continue }
            val date = parseDate(raw["date"].orEmpty().trim())
            if (date == null) { errors.add(RowError(rowNo, "IMPORT_DATE_INVALID", "date", raw["date"])); continue }
            val method = raw["method"].orEmpty().trim().lowercase().takeIf { it.isNotEmpty() }?.let { runCatching { AmortizationMethod.valueOf(it) }.getOrNull() }
            val mode = raw["mode"].orEmpty().trim().lowercase().takeIf { it.isNotEmpty() }?.let { runCatching { PrepaymentMode.valueOf(it) }.getOrNull() }
            val principal = Csv.parseDecimal(raw["principal"].orEmpty())?.let { Money.normalize(it) }
            val annualRate = Csv.parseDecimal(raw["annual_rate"].orEmpty())
            val termMonths = raw["term_months"].orEmpty().trim().toIntOrNull()
            val instalment = Csv.parseDecimal(raw["instalment"].orEmpty())?.let { Money.normalize(it) }
            val amount = Csv.parseDecimal(raw["amount"].orEmpty())?.let { Money.normalize(it) }
            val interest = Csv.parseDecimal(raw["interest"].orEmpty())?.let { Money.normalize(it) }
            val resulting = Csv.parseDecimal(raw["resulting_balance"].orEmpty())?.let { Money.normalize(it) }
            val startMode = raw["start_mode"].orEmpty().trim().lowercase().takeIf { it.isNotEmpty() }
                ?.let { runCatching { StartMode.valueOf(it) }.getOrNull() } ?: StartMode.current_balance
            val anchorDate = parseDate(raw["anchor_date"].orEmpty().trim())
            val anchorBalance = Csv.parseDecimal(raw["anchor_balance"].orEmpty())?.let { Money.normalize(it) }

            when (type) {
                "part" -> if (method == null || principal == null) { errors.add(RowError(rowNo, "IMPORT_PART_INVALID", "method", raw["method"])); continue }
                "revision" -> if (annualRate == null) { errors.add(RowError(rowNo, "IMPORT_RATE_INVALID", "annual_rate", raw["annual_rate"])); continue }
                "prepayment" -> if (amount == null || mode == null) { errors.add(RowError(rowNo, "IMPORT_PREPAYMENT_INVALID", "amount", raw["amount"])); continue }
            }
            rows.add(Row(name, label, type, date, method, principal, annualRate, termMonths, instalment, amount, mode, interest, resulting, startMode, anchorDate, anchorBalance))
        }
        return Parsed(result.rows.size, rows, errors)
    }

    private fun parseDate(s: String): LocalDate? =
        if (s.isEmpty()) null else try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }

    private data class Row(
        val liabilityName: String,
        val partLabel: String,
        val recordType: String,
        val date: LocalDate?,
        val method: AmortizationMethod?,
        val principal: BigDecimal?,
        val annualRate: BigDecimal?,
        val termMonths: Int?,
        val instalment: BigDecimal?,
        val amount: BigDecimal?,
        val mode: PrepaymentMode?,
        val interest: BigDecimal?,
        val resultingBalance: BigDecimal?,
        val startMode: StartMode,
        val anchorDate: LocalDate?,
        val anchorBalance: BigDecimal?,
    )

    private data class Parsed(val total: Int, val rows: List<Row>, val errors: List<RowError>)
}

internal object AmortizationCsvLabels {
    /** Only real labels match; an exported "#N" placeholder is never matched positionally against a null label.
     *  Only real labels match; an exported "#N" placeholder is never matched positionally against a null label. */
    fun matches(partLabel: String?, exportedLabel: String): Boolean =
        partLabel != null && partLabel == exportedLabel
}
