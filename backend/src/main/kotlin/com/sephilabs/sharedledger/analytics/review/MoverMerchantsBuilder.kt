package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.recurring.ExpenseRecord
import com.sephilabs.sharedledger.analytics.recurring.PayeeVariantClusterer
import com.sephilabs.sharedledger.common.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class MoverMerchantDriver(
    val merchant: String,
    val anonymized: Boolean,
    val total: BigDecimal,
    val purchases: Int,
    val shareOfCategoryPct: Double,
)

data class MoverDriversSummary(
    val total: BigDecimal,
    val purchases: Int,
    val shareOfCategoryPct: Double,
)

data class MoverCategoryBreakdown(
    val categoryCode: String,
    val periodTotal: BigDecimal,
    val periodPurchases: Int,
    val topMerchants: List<MoverMerchantDriver>,
    val topMerchantsSummary: MoverDriversSummary?,
    val otherMerchants: OtherMerchantsAggregateDto?,
)

/** Pre-computes sanitized top merchant drivers inside each period increase category so the AI can
 *  explain what an increase consisted of without receiving raw transactions or doing arithmetic. */
object MoverMerchantsBuilder {

    fun build(
        nonRefund12m: List<ExpenseRecord>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        increaseCategoryCodes: Set<String>,
        recurringKeysByCategory: Map<String, Set<String>>,
        memberTokens: Set<String>,
        maxMerchants: Int = SpendingReviewConstants.MAX_MOVER_MERCHANTS,
        splitRules: List<PayeeVariantClusterer.SplitRule> = emptyList(),
    ): Map<String, MoverCategoryBreakdown> {
        if (increaseCategoryCodes.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MoverCategoryBreakdown>()

        for (categoryCode in increaseCategoryCodes) {
            val cat12m = nonRefund12m.filter { it.categoryCode == categoryCode && it.amount.signum() > 0 }
            val catPeriod = cat12m.filter { !it.occurrenceDate.isBefore(periodStart) && !it.occurrenceDate.isAfter(periodEnd) }
            if (catPeriod.isEmpty()) continue

            val periodTotal = Money.normalize(catPeriod.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
            if (periodTotal.signum() <= 0) continue

            val recKeys = recurringKeysByCategory[categoryCode].orEmpty()
            val clusters12m = PayeeVariantClusterer.clusterByCategory(cat12m, splitRules)

            data class CandidateDriver(
                val label: String,
                val anonymized: Boolean,
                val total: BigDecimal,
                val purchases: Int,
            )

            val drivers = mutableListOf<CandidateDriver>()
            for (cluster in clusters12m) {
                val periodRecs = cluster.records.filter {
                    !it.occurrenceDate.isBefore(periodStart) && !it.occurrenceDate.isAfter(periodEnd)
                }
                if (periodRecs.isEmpty()) continue
                val total = Money.normalize(periodRecs.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
                val purchases12m = cluster.records.size
                val isRecurring = cluster.memberKeys.any { it in recKeys } || cluster.canonicalKey in recKeys
                val bankDesc = cluster.records.lastOrNull { !it.bankDescription.isNullOrBlank() }?.bankDescription

                // Privacy contract: merchant names leave only when recurring OR >= MIN_MERCHANT_PURCHASES in 12m
                // AND passing MerchantSanitizer (no P2P, no person names, no IBANs, no household members).
                val cls = if (isRecurring || purchases12m >= SpendingReviewConstants.MIN_MERCHANT_PURCHASES) {
                    MerchantSanitizer.classifyForPayload(
                        localLabel = cluster.canonicalMerchant,
                        bankDescription = bankDesc,
                        categoryCode = categoryCode,
                        memberTokens = memberTokens,
                        occurrences = purchases12m,
                    )
                } else {
                    MerchantSanitizer.Classification(
                        MerchantSanitizer.Verdict.ANONYMIZE,
                        MerchantSanitizer.Reason.UNVERIFIED_SINGLE_TOKEN,
                        null,
                    )
                }

                val payloadLabel = if (cls.allowedInPayload) {
                    cls.sanitizedLabel!!
                } else {
                    MerchantSanitizer.anonymizedLabel("Counterparty", categoryCode)
                }
                drivers += CandidateDriver(
                    label = payloadLabel,
                    anonymized = !cls.allowedInPayload,
                    total = total,
                    purchases = periodRecs.size,
                )
            }

            // Include unlabelled period transactions as an anonymized driver if present (e.g. a single €1,111 blank entry).
            val unlabeledPeriod = catPeriod.filter { it.rawLabel.isNullOrBlank() }
            if (unlabeledPeriod.isNotEmpty()) {
                val unlTotal = Money.normalize(unlabeledPeriod.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
                drivers += CandidateDriver(
                    label = MerchantSanitizer.anonymizedLabel("Unlabelled purchase", categoryCode),
                    anonymized = true,
                    total = unlTotal,
                    purchases = unlabeledPeriod.size,
                )
            }

            val sorted = drivers.sortedByDescending { it.total }
            val top = sorted.take(maxMerchants).map { d ->
                MoverMerchantDriver(
                    merchant = d.label,
                    anonymized = d.anonymized,
                    total = d.total,
                    purchases = d.purchases,
                    shareOfCategoryPct = sharePct(d.total, periodTotal),
                )
            }
            val tail = sorted.drop(maxMerchants)
            val other = if (tail.isNotEmpty()) {
                OtherMerchantsAggregateDto(
                    total = Money.normalize(tail.fold(BigDecimal.ZERO) { a, d -> a + d.total }),
                    purchases = tail.sumOf { it.purchases },
                    merchantCount = tail.size,
                )
            } else null

            val topSummary = if (top.size >= 2) {
                val sumTop = Money.normalize(top.fold(BigDecimal.ZERO) { a, d -> a + d.total })
                MoverDriversSummary(
                    total = sumTop,
                    purchases = top.sumOf { it.purchases },
                    shareOfCategoryPct = sharePct(sumTop, periodTotal),
                )
            } else null

            result[categoryCode] = MoverCategoryBreakdown(
                categoryCode = categoryCode,
                periodTotal = periodTotal,
                periodPurchases = catPeriod.size,
                topMerchants = top,
                topMerchantsSummary = topSummary,
                otherMerchants = other,
            )
        }
        return result
    }

    private fun sharePct(part: BigDecimal, whole: BigDecimal): Double =
        if (whole.signum() > 0) {
            BigDecimal.valueOf(part.divide(whole, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0)
                .setScale(2, RoundingMode.HALF_EVEN)
                .toDouble()
        } else 0.0
}
