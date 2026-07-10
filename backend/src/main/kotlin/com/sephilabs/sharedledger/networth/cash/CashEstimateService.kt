package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.lending.LendingPaymentRepository
import com.sephilabs.sharedledger.lending.LendingRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Net of the marked flows over a window, split by type so the UI can show a breakdown.
 * From cash's perspective each flow carries its own sign (bidirectional).
 */
data class CashFlowBreakdown(
    val transactions: BigDecimal,
    val lendings: BigDecimal,
    val movements: BigDecimal,
) {
    val net: BigDecimal get() = transactions + lendings + movements
}

/**
 * The estimated cash at a date: the anchor (latest adjustment on/before the date) plus the net of
 * the marked flows dated strictly after the anchor. Null [anchorDate] means no adjustment exists —
 * cash has no series yet, so there is no estimate (callers fall back to carry-over behaviour).
 */
data class CashEstimate(
    val date: LocalDate,
    val anchorDate: LocalDate?,
    val anchorAmount: BigDecimal?,
    val flows: CashFlowBreakdown,
    val estimate: BigDecimal?,
)

/**
 * Computes cash on demand (never cached): the manual adjustment series is the truth, and between
 * adjustments cash is estimated from the signed flows the household marked as affecting cash.
 * See [CashAdjustment] for the end-of-day convention.
 */
@Service
class CashEstimateService(
    private val adjustments: CashAdjustmentRepository,
    private val settingsRepo: CashEstimateSettingsRepository,
    private val transactions: TransactionRepository,
    private val movements: MovementRepository,
    private val lendings: LendingRepository,
    private val lendingPayments: LendingPaymentRepository,
) {

    @Transactional
    fun getOrCreateSettings(householdId: UUID): CashEstimateSettings =
        settingsRepo.findById(householdId).orElseGet {
            settingsRepo.save(CashEstimateSettings(householdId = householdId))
        }

    @Transactional
    fun updateSettings(
        householdId: UUID,
        includeTransactions: Boolean,
        includeLendings: Boolean,
        includeMovements: Boolean,
        by: User,
    ): CashEstimateSettings {
        val s = getOrCreateSettings(householdId)
        s.includeTransactions = includeTransactions
        s.includeLendings = includeLendings
        s.includeMovements = includeMovements
        s.updatedByUserId = by.id
        s.updatedAt = Instant.now()
        return s
    }

    /**
     * The cash estimate at [date]. If no adjustment exists on or before [date], returns an estimate
     * with null [CashEstimate.estimate] (no anchor to compute from) — cash then behaves as today.
     */
    @Transactional(readOnly = true)
    fun estimateAt(householdId: UUID, date: LocalDate): CashEstimate {
        val anchor = adjustments
            .findFirstByHouseholdIdAndAdjustmentDateLessThanEqualOrderByAdjustmentDateDescCreatedAtDesc(householdId, date)
            ?: return CashEstimate(date, null, null, CashFlowBreakdown(ZERO, ZERO, ZERO), null)

        val settings = getOrCreateSettings(householdId)
        // Flows dated strictly after the anchor day (end-of-day convention) and up to the date.
        val from = anchor.adjustmentDate.plusDays(1)
        val flows = if (from.isAfter(date)) {
            CashFlowBreakdown(ZERO, ZERO, ZERO)
        } else {
            CashFlowBreakdown(
                transactions = if (settings.includeTransactions) netTransactions(householdId, from, date) else ZERO,
                lendings = if (settings.includeLendings) netLendings(householdId, from, date) else ZERO,
                movements = if (settings.includeMovements) netMovements(householdId, from, date) else ZERO,
            )
        }
        return CashEstimate(
            date = date,
            anchorDate = anchor.adjustmentDate,
            anchorAmount = anchor.amount,
            flows = flows,
            estimate = Money.normalize(anchor.amount + flows.net),
        )
    }

    /** Transactions: income adds to cash, expense subtracts. */
    private fun netTransactions(householdId: UUID, from: LocalDate, to: LocalDate): BigDecimal =
        transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, from, to)
            .fold(ZERO) { acc, tx ->
                acc + if (tx.direction == Direction.income) tx.amount else tx.amount.negate()
            }

    /** Movements: withdrawal from an investment adds cash; contribution and debt payment subtract. */
    private fun netMovements(householdId: UUID, from: LocalDate, to: LocalDate): BigDecimal =
        movements.findInRange(householdId, from, to)
            .fold(ZERO) { acc, mv ->
                acc + if (mv.type == MovementType.withdrawal) mv.amount else mv.amount.negate()
            }

    /**
     * Lent: money lent out (principal on the lending's start date) leaves cash; a repayment received
     * comes in. Both are stored positive with implicit direction.
     */
    private fun netLendings(householdId: UUID, from: LocalDate, to: LocalDate): BigDecimal {
        val list = lendings.findAllByHouseholdId(householdId)
        if (list.isEmpty()) return ZERO
        var net = ZERO
        for (lending in list) {
            if (!lending.startDate.isBefore(from) && !lending.startDate.isAfter(to)) {
                net -= lending.principalAmount
            }
        }
        val payments = lendingPayments.findAllByLendingIdsOrderByPaymentDateAsc(list.map { it.id })
        for (p in payments) {
            if (!p.paymentDate.isBefore(from) && !p.paymentDate.isAfter(to)) net += p.amount
        }
        return net
    }

    private companion object {
        val ZERO: BigDecimal = BigDecimal.ZERO
    }
}
