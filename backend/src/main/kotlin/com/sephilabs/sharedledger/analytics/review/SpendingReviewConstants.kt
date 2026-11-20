package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.recurring.RecurringAction
import com.sephilabs.sharedledger.analytics.recurring.RecurringDetectionConstants
import java.math.BigDecimal

/** Named thresholds governing spending-review period eligibility, decisions, upcoming charges,
 *  merchant qualification, lumpy-category trimming, and deterministic report section caps. */
object SpendingReviewConstants {
    val RECURRING_ACTION_BY_CATEGORY: Map<String, RecurringAction> = RecurringDetectionConstants.RECURRING_ACTION_BY_CATEGORY

    fun resolveRecurringAction(categoryCode: String, essential: Boolean): RecurringAction =
        RecurringDetectionConstants.resolveRecurringAction(categoryCode, essential)

    const val AI_REVIEW_MIN_DAYS_ELAPSED: Int = 7

    const val RECURRING_LOOKBACK_MONTHS: Int = RecurringDetectionConstants.RECURRING_LOOKBACK_MONTHS

    // Decision memory thresholds
    const val DECISION_COMPARED_SNOOZE_MONTHS: Long = 12L
    val DECISION_RESURFACE_PCT: BigDecimal = BigDecimal("5.0")

    // Coming up (non-monthly quarterly/yearly charges only — no amount threshold)
    const val UPCOMING_WINDOW_DAYS: Long = 60L
    const val UPCOMING_QUARTERLY_TOLERANCE_DAYS: Long = 7L
    const val UPCOMING_YEARLY_TOLERANCE_DAYS: Long = 14L

    // Since last month amount-change thresholds
    val SINCE_LAST_MONTH_AMOUNT_DELTA_ABS: BigDecimal = BigDecimal("1.00")
    val SINCE_LAST_MONTH_AMOUNT_DELTA_PCT: BigDecimal = BigDecimal("2.0")

    const val MIN_MERCHANT_PURCHASES: Int = 3
    const val MIN_MERCHANT_CATEGORY_SHARE_PCT: Double = 5.0
    const val MIN_MERCHANT_NAME_LENGTH: Int = 2

    val LUMPY_MIN_TRIM_MONTHLY_EUR: BigDecimal = BigDecimal("15.00")
    val LUMPY_MIN_MEAN_MEDIAN_RATIO: BigDecimal = BigDecimal("1.25")
    const val LUMPY_MIN_NON_ZERO_MONTHS: Int = 2
    const val LUMPY_MIN_ACTIVE_MONTHS: Int = 6
    val HABITUAL_PEAK_OUTLIER_MIN_SHARE: BigDecimal = BigDecimal("0.50")

    val CUT_COMBINATION_TARGETS_EUR: List<Int> = listOf(100, 200, 300, 500)

    // Deterministic report section caps (sub-caps are ceilings, not quotas; sections fill in priority order).
    val SAVINGS_MIN_ANNUAL_EUR: BigDecimal = BigDecimal("100.00")
    val SUMMARY_IN_LINE_PCT: BigDecimal = BigDecimal("3.0")
    val SUMMARY_IN_LINE_ABS_EUR: BigDecimal = BigDecimal("25.00")

    const val MAX_SAVINGS_FINDINGS: Int = 6
    const val MAX_SAVINGS_CANCELLABLE: Int = 3
    const val MAX_SAVINGS_NEGOTIABLE: Int = 2
    const val MAX_SAVINGS_MERCHANTS: Int = 2
    const val MAX_SAVINGS_HABITUAL: Int = 2

    const val MAX_CHANGED_FINDINGS: Int = 6
    const val MAX_CHANGED_INCOME: Int = 1
    const val MAX_CHANGED_INCREASES: Int = 3
    const val MAX_CHANGED_NONE_PRICES: Int = 2
    const val MAX_CHANGED_ONE_OFFS: Int = 2

    const val MAX_BUDGET_FINDINGS: Int = 4
    val BUDGET_UNDERESTIMATED_MIN_RATIO: BigDecimal = BigDecimal("1.25")
    val BUDGET_CONCENTRATED_MEDIAN_MAX_RATIO: BigDecimal = BigDecimal("0.10")
    val BUDGET_CONCENTRATED_TOP_SHARE_MIN: BigDecimal = BigDecimal("0.75")

    const val MAX_MOVERS_PER_LIST: Int = 3
    const val MAX_MOVER_MERCHANTS: Int = 3

    const val ANALYTICS_AI_FOLLOWUP_MAX_CHARS: Int = 500

    /** Internal/jargon terms forbidden in both AI-written prose and deterministic fallback templates. */
    val BANNED_TERMS_EN: List<String> = listOf(
        "lumpy",
        "baseline",
        "baselines",
        "flag",
        "flags",
        "flagged",
        "payload",
        "truncated",
        "truncation",
        "trimming potential",
    )

    val BANNED_TERMS_ES: List<String> = listOf(
        "línea base",
        "linea base",
        "bandera",
        "banderas",
        "carga útil",
        "carga util",
        "truncado",
        "truncada",
        "truncamiento",
        "potencial de recorte",
    )

    val BANNED_TERMS_ALL: List<String> = BANNED_TERMS_EN + BANNED_TERMS_ES
}
