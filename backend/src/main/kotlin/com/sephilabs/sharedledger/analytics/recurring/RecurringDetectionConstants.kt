package com.sephilabs.sharedledger.analytics.recurring

import java.math.BigDecimal
import java.text.Normalizer

enum class RecurringAction { cancellable, negotiable, none }

/** Shared recurring-detection, payee-clustering, and actionability constants used by both
 *  the Spending review package and the shared Forecast/projection estimator. */
object RecurringDetectionConstants {

    private val DIACRITICS = Regex("""\p{InCombiningDiacriticalMarks}+""")

    fun foldAscii(input: String): String =
        DIACRITICS.replace(Normalizer.normalize(input, Normalizer.Form.NFD), "").lowercase()

    val RECURRING_ACTION_BY_CATEGORY: Map<String, RecurringAction> = mapOf(
        // cancellable: subscriptions, lottery, memberships, discretionary services
        "outings.subscriptions" to RecurringAction.cancellable,
        "outings.lottery" to RecurringAction.cancellable,
        "outings.hobbies" to RecurringAction.cancellable,
        "home.services" to RecurringAction.cancellable,
        // negotiable: utilities, insurance, telecom, mortgage rate, avoidable bank fees
        "home.utilities" to RecurringAction.negotiable,
        "home.insurance_fees" to RecurringAction.negotiable,
        "home.mortgage" to RecurringAction.negotiable,
        "financial.fees" to RecurringAction.negotiable,
        // none: taxes, fines, rent, loan principal, repairs, health, fuel, public transit, groceries
        "home.taxes" to RecurringAction.none,
        "home.rent" to RecurringAction.none,
        "home.repairs" to RecurringAction.none,
        "transport.fuel" to RecurringAction.none,
        "transport.public" to RecurringAction.none,
        "transport.car_maintenance" to RecurringAction.none,
        "groceries.groceries" to RecurringAction.none,
        "health.medical" to RecurringAction.none,
        "health.pharmacy" to RecurringAction.none,
    )

    private val NONE_KEYWORDS = listOf("tax", "impuesto", "fine", "multa", "principal", "loan", "prestamo", "rent", "alquiler")
    private val NEGOTIABLE_KEYWORDS = listOf(
        "util", "energ", "insur", "segur", "telecom", "internet", "mobile", "movil", "fibra",
        "mortgag", "hipotec", "fee", "comision",
    )
    private val CANCELLABLE_KEYWORDS = listOf("subscrip", "suscrip", "lotter", "loter", "member", "gym", "club", "stream")

    fun resolveRecurringAction(categoryCode: String, essential: Boolean): RecurringAction {
        RECURRING_ACTION_BY_CATEGORY[categoryCode]?.let { return it }
        val lower = categoryCode.lowercase()
        if (NONE_KEYWORDS.any { lower.contains(it) }) return RecurringAction.none
        if (NEGOTIABLE_KEYWORDS.any { lower.contains(it) }) return RecurringAction.negotiable
        if (CANCELLABLE_KEYWORDS.any { lower.contains(it) }) return RecurringAction.cancellable
        return if (!essential) RecurringAction.cancellable else RecurringAction.none
    }

    const val RECURRING_LOOKBACK_MONTHS: Int = 24
    const val RECURRING_MIN_OCCURRENCES_WEEKLY: Int = 4
    const val RECURRING_MIN_OCCURRENCES_MONTHLY: Int = 3
    const val RECURRING_MIN_OCCURRENCES_QUARTERLY: Int = 3
    const val RECURRING_MIN_OCCURRENCES_YEARLY: Int = 2

    val RECURRING_WEEKLY_GAP_DAYS: IntRange = 5..9
    val RECURRING_MONTHLY_GAP_DAYS: IntRange = 25..35
    val RECURRING_QUARTERLY_GAP_DAYS: IntRange = 80..100
    val RECURRING_YEARLY_GAP_DAYS: IntRange = 345..385

    val RECURRING_AMOUNT_TOLERANCE_PCT: BigDecimal = BigDecimal("0.15")
    val RECURRING_AMOUNT_TOLERANCE_ABS: BigDecimal = BigDecimal("2.00")
    const val RECURRING_MIN_STABILITY_RATIO: Double = 0.70
    const val RECURRING_YOY_WINDOW_DAYS: Long = 45L
    val RECURRING_MIN_ANNUAL_COST: BigDecimal = BigDecimal("24.00")
    val RECURRING_YEARLY_MIN_AMOUNT: BigDecimal = BigDecimal("25.00")
    const val RECURRING_MAX_STALE_DAYS_WEEKLY: Long = 45L
    const val RECURRING_MAX_STALE_DAYS_MONTHLY: Long = 105L
    const val RECURRING_MAX_STALE_DAYS_QUARTERLY: Long = 180L

    const val PAYEE_VARIANT_DISTINCTIVE_TOKEN_MIN_LEN: Int = 5
    const val PAYEE_VARIANT_SHORT_KEY_MIN_LEN: Int = 5
    const val PAYEE_VARIANT_LONG_KEY_MIN_LEN: Int = 8
    const val PAYEE_VARIANT_MAX_EDIT_DIST_SHORT: Int = 1
    const val PAYEE_VARIANT_MAX_EDIT_DIST_LONG: Int = 2
}
