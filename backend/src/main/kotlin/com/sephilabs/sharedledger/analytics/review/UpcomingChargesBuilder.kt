package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.recurring.DetectedCadence
import com.sephilabs.sharedledger.analytics.recurring.RecurringAction
import java.time.LocalDate

/** Builds the deterministic "Coming up" list of non-monthly recurring charges (quarterly and yearly only;
 *  no monthly charges and no amount threshold) expected within the configured lookahead window. */
object UpcomingChargesBuilder {

    fun build(
        recurringCharges: List<RecurringChargeCandidateDto>,
        asOf: LocalDate,
        windowDays: Long = SpendingReviewConstants.UPCOMING_WINDOW_DAYS,
    ): List<UpcomingChargeDto> {
        val horizonEnd = asOf.plusDays(windowDays)
        val out = mutableListOf<Pair<LocalDate, UpcomingChargeDto>>()

        for (r in recurringCharges) {
            if (r.cadence != DetectedCadence.quarterly && r.cadence != DetectedCadence.yearly) continue
            val lastDate = r.lastOccurrenceDate ?: continue

            val tol = if (r.cadence == DetectedCadence.quarterly) {
                SpendingReviewConstants.UPCOMING_QUARTERLY_TOLERANCE_DAYS
            } else {
                SpendingReviewConstants.UPCOMING_YEARLY_TOLERANCE_DAYS
            }

            var expected = stepForward(lastDate, r.cadence)
            while (expected.plusDays(tol).isBefore(asOf)) {
                expected = stepForward(expected, r.cadence)
            }
            if (expected.isAfter(horizonEnd)) continue
            if (expected.isBefore(asOf.minusDays(tol))) continue

            val from = expected.minusDays(tol)
            val to = expected.plusDays(tol)
            out += expected to UpcomingChargeDto(
                id = "upc_${r.id}",
                subjectKey = r.subjectKey,
                merchant = r.merchant,
                payloadLabel = r.payloadLabel,
                anonymized = r.anonymized,
                categoryCode = r.categoryCode,
                groupCode = r.groupCode,
                cadence = r.cadence,
                action = r.action,
                expectedFrom = from,
                expectedTo = to,
                typicalAmount = r.latestAmount,
                compareBeforeRenewal = r.action == RecurringAction.negotiable,
                kept = r.decision?.decisionType == SpendingReviewDecisionType.keep,
                txFilter = r.txFilter,
                explorerLink = r.explorerLink,
            )
        }

        return out
            .sortedWith(
                compareBy<Pair<LocalDate, UpcomingChargeDto>> { it.first }
                    .thenByDescending { it.second.typicalAmount }
                    .thenBy { it.second.id },
            )
            .map { it.second }
    }

    private fun stepForward(date: LocalDate, cadence: DetectedCadence): LocalDate = when (cadence) {
        DetectedCadence.quarterly -> date.plusMonths(3)
        DetectedCadence.yearly -> date.plusYears(1)
        else -> date
    }
}
