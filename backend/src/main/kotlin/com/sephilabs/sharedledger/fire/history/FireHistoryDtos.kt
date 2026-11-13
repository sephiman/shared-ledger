package com.sephilabs.sharedledger.fire.history

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.fire.FireTierKey
import java.math.BigDecimal
import java.time.LocalDate

/** The photo as if looked at on `date`: that day's wealth and spending window, today's settings. */
data class FireHistoryPoint(
    val date: LocalDate,
    val snapshotDate: LocalDate,
    val current: Boolean,
    val coveragePercent: Double,
    val estimatedYear: Int?,
    val probability: Double?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val wealth: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val target: BigDecimal,
)

enum class FireHistoryOmissionReason { insufficient_spending_history, no_target, beyond_point_limit }

data class FireHistoryOmission(val reason: FireHistoryOmissionReason, val count: Int)

data class FireHistoryResponse(
    val tier: FireTierKey,
    val horizonYear: Int,
    /** Oldest first; the last one is the current photo. Empty when no point can be shown. */
    val points: List<FireHistoryPoint>,
    val omitted: List<FireHistoryOmission>,
    /** True when every point uses today's typed contribution rather than one derived as of its date. */
    val manualContribution: Boolean,
    /** True when the reference tier's spending base is a typed amount, so it is the same at every point. */
    val manualSpending: Boolean,
    /** Today against the point closest to a year earlier; null with fewer than two points. */
    val attribution: FireAttribution?,
)

/** A change in coverage (percentage points) split into causes that add up exactly; amounts in euros alongside. */
data class FireAttribution(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    /** True when movements cannot separate contributions from the market, so only `wealthPoints` is meaningful. */
    val merged: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val totalPoints: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val contributedPoints: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val marketPoints: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val wealthPoints: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val needPoints: BigDecimal,
    /** Unrounded parts, for checking they add up. */
    val exact: FireAttributionExact,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val wealthFrom: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val wealthTo: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetFrom: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val targetTo: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val contributedAmount: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val marketAmount: BigDecimal?,
)

data class FireAttributionExact(
    val total: Double,
    val contributed: Double?,
    val market: Double?,
    val wealth: Double,
    val need: Double,
)
