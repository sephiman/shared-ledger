package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.bank.aisuggestion.AI_SUGGESTION_MIN_CONFIDENCE
import com.sephilabs.sharedledger.common.Money
import java.math.BigDecimal
import java.time.LocalDate

sealed interface FireQuestionInterpretation {
    data class Understood(
        val whatIf: FireQuestionWhatIf?,
        val unsupported: List<FireQuestionUnsupported>,
    ) : FireQuestionInterpretation

    data class NotUnderstood(val reason: FireQuestionRejection) : FireQuestionInterpretation
}

object FireQuestionValidator {

    fun validate(answer: ParsedFireQuestionAnswer.Read, today: LocalDate): FireQuestionInterpretation {
        if (answer.confidence < AI_SUGGESTION_MIN_CONFIDENCE) {
            return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.low_confidence)
        }

        val resolvedEvents = mutableListOf<FireQuestionEvent>()
        for (ev in answer.events) {
            val year = ev.year ?: ev.yearsFromNow?.let { today.year + it }
                ?: return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.malformed)
            if (year <= today.year || year > 2200) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            if (ev.amount.signum() == 0) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            resolvedEvents += FireQuestionEvent(
                year = year,
                kind = ev.kind,
                amount = Money.normalize(ev.amount),
                label = ev.label,
                template = ev.template,
            )
        }

        val lumpSum = answer.lumpSum?.let {
            if (it < BigDecimal.ZERO) return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            it.takeIf { v -> v.signum() > 0 }?.let(Money::normalize)
        }

        val returnShift = answer.returnShiftPct?.let {
            if (it < BigDecimal("-20") || it > BigDecimal("20")) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            it.takeIf { v -> v.signum() != 0 }
        }

        answer.monthlyContribution?.let { c ->
            if (c.kind == FireAdjustmentKind.set && c.value < BigDecimal.ZERO) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            if (c.kind == FireAdjustmentKind.percent && c.value < BigDecimal("-100")) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
        }

        answer.monthlySpending?.let { s ->
            if (s.kind == FireAdjustmentKind.set && s.value <= BigDecimal.ZERO) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            if (s.kind == FireAdjustmentKind.percent && s.value <= BigDecimal("-100")) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
        }

        answer.targetYear?.let { y ->
            if (y.kind == FireYearAdjustmentKind.set && (y.value < today.year || y.value > 2200)) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            if (y.kind == FireYearAdjustmentKind.delta && (y.value < -80 || y.value > 80)) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
        }

        answer.safeWithdrawalRatePct?.let { swr ->
            if (swr.kind == FireAdjustmentKind.set && (swr.value <= BigDecimal.ZERO || swr.value > BigDecimal("100"))) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
            if (swr.kind == FireAdjustmentKind.percent && swr.value <= BigDecimal("-100")) {
                return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.invalid_range)
            }
        }

        val whatIf = FireQuestionWhatIf(
            monthlyContribution = answer.monthlyContribution,
            monthlySpending = answer.monthlySpending,
            targetYear = answer.targetYear,
            safeWithdrawalRatePct = answer.safeWithdrawalRatePct,
            lumpSum = lumpSum,
            returnShiftPct = returnShift,
            cashFlowEvents = resolvedEvents,
        ).takeUnless { it.isEmpty }

        if (whatIf == null && answer.unsupported.isEmpty()) {
            return FireQuestionInterpretation.NotUnderstood(FireQuestionRejection.nothing_asked)
        }
        return FireQuestionInterpretation.Understood(whatIf, answer.unsupported)
    }
}
