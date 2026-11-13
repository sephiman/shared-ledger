package com.sephilabs.sharedledger.fire.levers

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.fire.FirePreviewRequest
import com.sephilabs.sharedledger.fire.FireReferenceReach
import com.sephilabs.sharedledger.fire.FireTierKey
import java.math.BigDecimal
import java.math.RoundingMode

/** The levers the "what moves your year" table tries, each a plain what-if on today's photo. */
object FireLeverValues {
    val CONTRIBUTION_STEP: BigDecimal = BigDecimal("100")
    val SPENDING_CUT_PCT: BigDecimal = BigDecimal("5")
    val LUMP_SUM: BigDecimal = BigDecimal("10000")
    val WITHDRAWAL_RATE_STEP: BigDecimal = BigDecimal("0.5")
    val RETURN_STEP_PCT: BigDecimal = BigDecimal("1")
}

enum class FireLeverKey { contribute_more, spend_less, lump_sum, safer_withdrawal, returns_up, returns_down }

/** One lever: the what-if it stands for (null when it cannot apply) and the label's values. */
class FireLever(
    val key: FireLeverKey,
    val outOfYourHands: Boolean,
    val preview: FirePreviewRequest?,
    val amount: BigDecimal? = null,
    val percent: BigDecimal? = null,
    val withdrawalFromPct: BigDecimal? = null,
    val withdrawalToPct: BigDecimal? = null,
)

/** Builds the levers from the photo's own inputs, so each one is exactly what the what-if would send. */
fun fireLevers(base: FireReferenceReach): List<FireLever> {
    val cut = BigDecimal.ONE - FireLeverValues.SPENDING_CUT_PCT.movePointLeft(2)
    val spendingApplies = base.tier != FireTierKey.custom && base.totalMonthlySpending.signum() > 0
    val saferRate = base.safeWithdrawalRatePct - FireLeverValues.WITHDRAWAL_RATE_STEP
    return listOf(
        FireLever(
            key = FireLeverKey.contribute_more,
            outOfYourHands = false,
            preview = FirePreviewRequest(monthlyContribution = Money.normalize(base.monthlyContribution + FireLeverValues.CONTRIBUTION_STEP)),
            amount = FireLeverValues.CONTRIBUTION_STEP,
        ),
        FireLever(
            key = FireLeverKey.spend_less,
            outOfYourHands = false,
            preview = if (spendingApplies) {
                FirePreviewRequest(
                    monthlySpending = scaled(base.totalMonthlySpending, cut),
                    monthlyEssentialSpending = scaled(base.essentialMonthlySpending, cut).takeIf { it.signum() > 0 },
                )
            } else {
                null
            },
            percent = FireLeverValues.SPENDING_CUT_PCT,
        ),
        FireLever(
            key = FireLeverKey.lump_sum,
            outOfYourHands = false,
            preview = FirePreviewRequest(lumpSum = FireLeverValues.LUMP_SUM),
            amount = FireLeverValues.LUMP_SUM,
        ),
        FireLever(
            key = FireLeverKey.safer_withdrawal,
            outOfYourHands = false,
            preview = if (saferRate.signum() > 0) FirePreviewRequest(safeWithdrawalRatePct = saferRate) else null,
            withdrawalFromPct = base.safeWithdrawalRatePct,
            withdrawalToPct = saferRate,
        ),
        FireLever(
            key = FireLeverKey.returns_up,
            outOfYourHands = true,
            preview = FirePreviewRequest(returnShiftPct = FireLeverValues.RETURN_STEP_PCT),
            percent = FireLeverValues.RETURN_STEP_PCT,
        ),
        FireLever(
            key = FireLeverKey.returns_down,
            outOfYourHands = true,
            preview = FirePreviewRequest(returnShiftPct = FireLeverValues.RETURN_STEP_PCT.negate()),
            percent = FireLeverValues.RETURN_STEP_PCT,
        ),
    )
}

private fun scaled(value: BigDecimal, factor: BigDecimal): BigDecimal =
    value.multiply(factor).setScale(Money.SCALE, RoundingMode.HALF_EVEN)

data class FireLeverRow(
    val key: FireLeverKey,
    val outOfYourHands: Boolean,
    /** False when the lever cannot change the reference target (spending levers on an own target). */
    val applicable: Boolean,
    /** The what-if to load when the row is opened; null when not applicable. */
    val preview: FirePreviewRequest?,
    val estimatedYear: Int?,
    val probability: Double?,
    /** Estimated year minus today's; null when either side is beyond the horizon. */
    val yearChange: Int?,
    val probabilityChangePoints: Double?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val percent: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val withdrawalFromPct: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val withdrawalToPct: BigDecimal?,
)

data class FireLeversResponse(
    val tier: FireTierKey,
    val horizonYear: Int,
    val baselineYear: Int?,
    val baselineProbability: Double?,
    /** Most helpful first by change in the chance of getting there; rows that delay come last, not hidden. */
    val rows: List<FireLeverRow>,
)
