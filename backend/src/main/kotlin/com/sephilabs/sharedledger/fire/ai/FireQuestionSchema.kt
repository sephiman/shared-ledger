package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.fire.CashFlowEventKind
import java.math.BigDecimal

enum class FireAdjustmentKind { set, delta, percent }

enum class FireYearAdjustmentKind { set, delta }

enum class FireQuestionUnsupportedKind { tax, inflation, asset_class, tier, other }

data class FireDecimalChange(val kind: FireAdjustmentKind, val value: BigDecimal)

data class FireYearChange(val kind: FireYearAdjustmentKind, val value: Int)

data class FireParsedEvent(
    val year: Int?,
    val yearsFromNow: Int?,
    val kind: CashFlowEventKind,
    val amount: BigDecimal,
    val label: String,
    val template: String?,
)

data class FireQuestionEvent(
    val year: Int,
    val kind: CashFlowEventKind,
    val amount: BigDecimal,
    val label: String,
    val template: String?,
)

data class FireQuestionUnsupported(val kind: FireQuestionUnsupportedKind, val label: String)

data class FireQuestionWhatIf(
    val monthlyContribution: FireDecimalChange? = null,
    val monthlySpending: FireDecimalChange? = null,
    val targetYear: FireYearChange? = null,
    val safeWithdrawalRatePct: FireDecimalChange? = null,
    val lumpSum: BigDecimal? = null,
    val returnShiftPct: BigDecimal? = null,
    val cashFlowEvents: List<FireQuestionEvent> = emptyList(),
) {
    val isEmpty: Boolean
        get() = monthlyContribution == null &&
            monthlySpending == null &&
            targetYear == null &&
            safeWithdrawalRatePct == null &&
            lumpSum == null &&
            returnShiftPct == null &&
            cashFlowEvents.isEmpty()
}

enum class FireQuestionRejection {
    unreadable,
    malformed,
    low_confidence,
    nothing_asked,
    invalid_range,
}

object FireQuestionSchema {
    const val VERSION = "fq1"
    const val MAX_UNSUPPORTED = 5
    const val MAX_UNSUPPORTED_LABEL = 40
    const val MAX_EVENTS = 6
    const val MAX_EVENT_LABEL = 60
    val TEMPLATES = setOf("mortgage_end", "state_pension", "part_time_income", "big_expense", "windfall")
}
