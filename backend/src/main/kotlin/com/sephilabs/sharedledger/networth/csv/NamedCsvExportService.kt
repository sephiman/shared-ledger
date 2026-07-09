package com.sephilabs.sharedledger.networth.csv

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.networth.amortization.AmortizationEntryRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationPartRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationPrepaymentRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationRateRevisionRepository
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.asset.AssetValueEntryRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntryRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * CSV export for named assets and liabilities (+ amortization). Symmetric to the portfolio/lending
 * exports: it dumps both the entities and their dated history so an export re-imports faithfully.
 * Amortization exports both the input data (parts/revisions/prepayments) and the generated
 * instalments, discriminated by a `record_type` column.
 */
@Service
class NamedCsvExportService(
    private val assets: AssetRepository,
    private val assetValues: AssetValueEntryRepository,
    private val liabilities: LiabilityRepository,
    private val liabilityBalances: LiabilityBalanceEntryRepository,
    private val parts: AmortizationPartRepository,
    private val revisions: AmortizationRateRevisionRepository,
    private val prepayments: AmortizationPrepaymentRepository,
    private val entries: AmortizationEntryRepository,
) {

    @Transactional(readOnly = true)
    fun exportAssets(householdId: UUID): String {
        val sb = StringBuilder()
        sb.append(Csv.row("name", "type", "active", "value_date", "value"))
        for (asset in assets.findAllByHouseholdIdOrderByNameAsc(householdId)) {
            val history = assetValues.findAllByAssetIdOrderByValueDateDescCreatedAtDesc(asset.id).sortedBy { it.valueDate }
            if (history.isEmpty()) {
                sb.append(Csv.row(asset.name, asset.type, asset.active, "", ""))
            } else {
                for (e in history) sb.append(Csv.row(asset.name, asset.type, asset.active, e.valueDate, Csv.decimal(e.value)))
            }
        }
        return sb.toString()
    }

    @Transactional(readOnly = true)
    fun exportLiabilities(householdId: UUID): String {
        val sb = StringBuilder()
        sb.append(Csv.row("name", "active", "amortizable", "charge_day", "balance_date", "balance"))
        for (l in liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)) {
            val history = liabilityBalances.findAllByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(l.id).sortedBy { it.balanceDate }
            val chargeDay = l.chargeDay?.toString() ?: ""
            if (history.isEmpty()) {
                sb.append(Csv.row(l.name, l.active, l.amortizable, chargeDay, "", ""))
            } else {
                for (e in history) sb.append(Csv.row(l.name, l.active, l.amortizable, chargeDay, e.balanceDate, Csv.decimal(e.balance)))
            }
        }
        return sb.toString()
    }

    @Transactional(readOnly = true)
    fun exportAmortization(householdId: UUID): String {
        val sb = StringBuilder()
        sb.append(Csv.row(
            "liability_name", "part_label", "record_type", "date", "method",
            "principal", "annual_rate", "term_months", "instalment", "amount", "mode", "interest", "resulting_balance",
            "start_mode", "anchor_date", "anchor_balance",
        ))
        for (l in liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)) {
            if (!l.amortizable) continue
            val partList = parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(l.id)
            partList.forEachIndexed { idx, part ->
                val label = partLabel(part.label, idx)
                sb.append(Csv.row(
                    l.name, label, "part", part.startDate, part.method,
                    Csv.decimal(part.originalPrincipal), Csv.decimal(part.annualRate),
                    part.termMonths?.toString() ?: "", part.instalment?.let { Csv.decimal(it) } ?: "",
                    "", "", "", "",
                    part.startMode, part.anchorDate ?: "", part.anchorBalance?.let { Csv.decimal(it) } ?: "",
                ))
                for (r in revisions.findAllByPartIdOrderByEffectiveDateAsc(part.id)) {
                    sb.append(Csv.row(l.name, label, "revision", r.effectiveDate, "", "", Csv.decimal(r.annualRate), "", "", "", "", "", "", "", "", ""))
                }
                for (p in prepayments.findAllByPartIdOrderByPrepaymentDateAsc(part.id)) {
                    sb.append(Csv.row(l.name, label, "prepayment", p.prepaymentDate, "", "", "", "", "", Csv.decimal(p.amount), p.mode, "", "", "", "", ""))
                }
                for (e in entries.findAllByPartIdOrderByChargeDateAsc(part.id)) {
                    // Entry rows reuse the `principal` column for the principal component (record_type disambiguates).
                    sb.append(Csv.row(
                        l.name, label, "entry", e.chargeDate, "", Csv.decimal(e.principal), "", "", "", "", "",
                        Csv.decimal(e.interest), Csv.decimal(e.resultingBalance), "", "", "",
                    ))
                }
            }
        }
        return sb.toString()
    }

    companion object {
        /** Stable identifier for a part within its liability: its label, or a positional fallback. */
        fun partLabel(label: String?, index: Int): String = label?.takeIf { it.isNotBlank() } ?: "#${index + 1}"
    }
}
