package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.common.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

/** Computes the deterministic "Since last month" diff by comparing the selected month's recurring charges
 *  and decision states against a pure clock-bound recomputation of the previous month (zero stored snapshots). */
object SinceLastMonthDiff {

    fun compute(
        current: SavingsCandidatesDto,
        previous: SavingsCandidatesDto,
        decisions: List<DecodedDecision>,
        asOfCurrent: LocalDate,
        asOfPrevious: LocalDate,
        currency: String,
        locale: String,
    ): List<SinceLastMonthItemDto> {
        val es = locale.startsWith("es", ignoreCase = true)
        val items = mutableListOf<SinceLastMonthItemDto>()
        val notRecurringDecisions = decisions.filter { it.decisionType == SpendingReviewDecisionType.not_recurring }

        val matchedCurrIds = mutableSetOf<String>()
        val matchedPrevIds = mutableSetOf<String>()
        val matchedPairs = mutableListOf<Pair<RecurringChargeCandidateDto, RecurringChargeCandidateDto>>()

        for (prev in previous.recurringCharges) {
            val match = current.recurringCharges.firstOrNull { curr ->
                curr.id !in matchedCurrIds && sameRecurringSubject(prev, curr)
            }
            if (match != null) {
                matchedPrevIds += prev.id
                matchedCurrIds += match.id
                matchedPairs += prev to match
            }
        }

        // 1. Stopped recurring charges (present last month, absent now, and not explicitly marked not_recurring).
        val stopped = previous.recurringCharges
            .filter { p ->
                p.id !in matchedPrevIds &&
                    notRecurringDecisions.none { nr -> nr.matches(DecisionSubjectKind.recurring, p.categoryCode, p.memberKeys.toSet()) }
            }
            .sortedByDescending { it.monthlyCost }

        for (p in stopped) {
            val amt = fmtMoney(p.monthlyCost, currency, locale)
            items += SinceLastMonthItemDto(
                id = "slm_stop_${p.subjectKey.ifBlank { p.id }}",
                changeType = "stopped_recurring",
                subjectKey = p.subjectKey.ifBlank { null },
                merchant = p.merchant,
                payloadLabel = p.payloadLabel,
                anonymized = p.anonymized,
                categoryCode = p.categoryCode,
                previousMonthlyCost = p.monthlyCost,
                currentMonthlyCost = BigDecimal.ZERO.setScale(2),
                deltaMonthly = Money.normalize(p.monthlyCost.negate()),
                cadence = p.cadence,
                title = if (es) "Dejó de cobrarse: ${p.merchant}" else "Stopped: ${p.merchant}",
                detail = if (es) {
                    "Ya no pagas ${p.merchant}: $amt al mes."
                } else {
                    "No longer paying ${p.merchant}: $amt a month."
                },
                txFilter = p.txFilter,
            )
        }

        // 2. Newly detected recurring charges.
        val added = current.recurringCharges
            .filter { c -> c.id !in matchedCurrIds }
            .sortedByDescending { it.monthlyCost }

        for (c in added) {
            val amt = fmtMoney(c.monthlyCost, currency, locale)
            items += SinceLastMonthItemDto(
                id = "slm_new_${c.subjectKey.ifBlank { c.id }}",
                changeType = "new_recurring",
                subjectKey = c.subjectKey.ifBlank { null },
                merchant = c.merchant,
                payloadLabel = c.payloadLabel,
                anonymized = c.anonymized,
                categoryCode = c.categoryCode,
                previousMonthlyCost = null,
                currentMonthlyCost = c.monthlyCost,
                deltaMonthly = c.monthlyCost,
                cadence = c.cadence,
                title = if (es) "Nuevo cargo recurrente: ${c.merchant}" else "New recurring charge: ${c.merchant}",
                detail = if (es) {
                    "${c.merchant} aparece ahora como cargo recurrente de $amt al mes."
                } else {
                    "${c.merchant} now appears as a recurring charge at $amt a month."
                },
                txFilter = c.txFilter,
            )
        }

        // 3. Amount changed since last month.
        val changed = matchedPairs
            .mapNotNull { (p, c) ->
                val diff = Money.normalize(c.monthlyCost - p.monthlyCost)
                val absDiff = diff.abs()
                val pct = if (p.monthlyCost.signum() > 0) {
                    absDiff.divide(p.monthlyCost, 4, RoundingMode.HALF_EVEN).multiply(BigDecimal("100"))
                } else BigDecimal.ZERO
                if (absDiff >= SpendingReviewConstants.SINCE_LAST_MONTH_AMOUNT_DELTA_ABS &&
                    pct >= SpendingReviewConstants.SINCE_LAST_MONTH_AMOUNT_DELTA_PCT
                ) {
                    Triple(p, c, diff)
                } else null
            }
            .sortedByDescending { it.third.abs() }

        for ((p, c, diff) in changed) {
            val prevFmt = fmtMoney(p.monthlyCost, currency, locale)
            val currFmt = fmtMoney(c.monthlyCost, currency, locale)
            val rose = diff.signum() > 0
            items += SinceLastMonthItemDto(
                id = "slm_chg_${c.subjectKey.ifBlank { c.id }}",
                changeType = "amount_changed",
                subjectKey = c.subjectKey.ifBlank { null },
                merchant = c.merchant,
                payloadLabel = c.payloadLabel,
                anonymized = c.anonymized,
                categoryCode = c.categoryCode,
                previousMonthlyCost = p.monthlyCost,
                currentMonthlyCost = c.monthlyCost,
                deltaMonthly = diff,
                cadence = c.cadence,
                title = if (es) {
                    if (rose) "Subió ${c.merchant}" else "Bajó ${c.merchant}"
                } else {
                    if (rose) "${c.merchant} went up" else "${c.merchant} went down"
                },
                detail = if (es) {
                    "${c.merchant} pasó de $prevFmt a $currFmt al mes."
                } else {
                    "${c.merchant} changed from $prevFmt to $currFmt a month."
                },
                txFilter = c.txFilter,
            )
        }

        // 4. Compared decisions that expired or resurfaced since the previous month.
        val prevEndInstant = asOfPrevious.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        for (d in decisions) {
            if (d.decisionType != SpendingReviewDecisionType.compared) continue
            if (d.decidedAt.isAfter(prevEndInstant)) continue

            val currRec = if (d.subjectKind == DecisionSubjectKind.recurring) {
                current.recurringCharges.firstOrNull { d.matches(DecisionSubjectKind.recurring, it.categoryCode, it.memberKeys.toSet()) }
            } else null
            val currMerch = if (d.subjectKind == DecisionSubjectKind.merchant) {
                current.topNonEssentialMerchants.firstOrNull { d.matches(DecisionSubjectKind.merchant, it.categoryCode, it.memberKeys.toSet()) }
            } else null
            if (currRec == null && currMerch == null) continue

            val prevRec = if (d.subjectKind == DecisionSubjectKind.recurring) {
                previous.recurringCharges.firstOrNull { d.matches(DecisionSubjectKind.recurring, it.categoryCode, it.memberKeys.toSet()) }
            } else null
            val prevMerch = if (d.subjectKind == DecisionSubjectKind.merchant) {
                previous.topNonEssentialMerchants.firstOrNull { d.matches(DecisionSubjectKind.merchant, it.categoryCode, it.memberKeys.toSet()) }
            } else null

            val prevCost = prevRec?.monthlyCost ?: prevMerch?.monthlyEquivalent
            val currCost = currRec?.monthlyCost ?: currMerch?.monthlyEquivalent
            val wasSnoozedPrev = d.isComparedSnoozed(asOfPrevious, prevCost)
            if (!wasSnoozedPrev) continue

            val merchant = currRec?.merchant ?: currMerch?.merchant ?: d.displayLabel
            val payloadLabel = currRec?.payloadLabel ?: currMerch?.payloadLabel
                ?: MerchantSanitizer.anonymizedLabel("Recurring charge", d.categoryCode)
            val anonymized = currRec?.anonymized ?: currMerch?.anonymized ?: true
            val txFilter = currRec?.txFilter ?: currMerch?.txFilter

            if (d.isComparedResurfaced(currCost)) {
                val baseFmt = fmtMoney(d.amountAtDecision ?: BigDecimal.ZERO, currency, locale)
                val currFmt = fmtMoney(currCost ?: BigDecimal.ZERO, currency, locale)
                items += SinceLastMonthItemDto(
                    id = "slm_resurf_${d.id}",
                    changeType = "decision_resurfaced",
                    subjectKey = d.subjectKey,
                    merchant = merchant,
                    payloadLabel = payloadLabel,
                    anonymized = anonymized,
                    categoryCode = d.categoryCode,
                    previousMonthlyCost = d.amountAtDecision,
                    currentMonthlyCost = currCost,
                    deltaMonthly = if (currCost != null && d.amountAtDecision != null) Money.normalize(currCost - d.amountAtDecision) else null,
                    cadence = currRec?.cadence,
                    title = if (es) "Vuelve a revisión: $merchant" else "Back for review: $merchant",
                    detail = if (es) {
                        "$merchant subió de $baseFmt a $currFmt al mes y vuelve a aparecer tras tu decisión de ya comparado."
                    } else {
                        "$merchant rose from $baseFmt to $currFmt a month, resurfacing your already-compared decision."
                    },
                    txFilter = txFilter,
                )
            } else if (d.isComparedExpired(asOfCurrent)) {
                items += SinceLastMonthItemDto(
                    id = "slm_exp_${d.id}",
                    changeType = "decision_expired",
                    subjectKey = d.subjectKey,
                    merchant = merchant,
                    payloadLabel = payloadLabel,
                    anonymized = anonymized,
                    categoryCode = d.categoryCode,
                    previousMonthlyCost = d.amountAtDecision,
                    currentMonthlyCost = currCost,
                    deltaMonthly = null,
                    cadence = currRec?.cadence,
                    title = if (es) "Recordatorio vencido: $merchant" else "Snooze expired: $merchant",
                    detail = if (es) {
                        "Venció tu recordatorio de doce meses para comparar $merchant."
                    } else {
                        "Your twelve-month reminder to compare $merchant expired."
                    },
                    txFilter = txFilter,
                )
            }
        }

        return items
    }

    private fun sameRecurringSubject(a: RecurringChargeCandidateDto, b: RecurringChargeCandidateDto): Boolean {
        if (a.categoryCode != b.categoryCode) return false
        if (a.subjectKey.isNotBlank() && a.subjectKey == b.subjectKey) return true
        val aKeys = a.memberKeys.toSet()
        val bKeys = b.memberKeys.toSet()
        return aKeys.isNotEmpty() && aKeys.any { it in bKeys }
    }

    private fun fmtMoney(value: BigDecimal, currency: String, locale: String): String =
        SpendingReviewRenderer.formatFigure(FigureSlot("m", "money", FigureKind.MONEY, value), currency, locale)
}
