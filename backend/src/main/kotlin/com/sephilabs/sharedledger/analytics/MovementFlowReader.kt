package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.networth.snapshot.CASH_CLASS
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Contributions to and withdrawals from each asset class in a period (the movements Money flow can draw), one entry
 *  per class and direction.
 *  A movement has no counterpart, so every one on a non-cash class counts as cash↔class: a switch between two
 *  classes shows as a withdrawal from one and a contribution to the other. Movements on the cash class itself and
 *  debt payments are not drawn. Amounts are already in the household currency. */
@Component
class MovementFlowReader(private val movements: MovementRepository) {

    fun read(householdId: UUID, from: LocalDate, to: LocalDate): List<MoneyFlowMovementClass> =
        movements.findInRange(householdId, from, to)
            .filter { it.type != MovementType.debt_payment && it.assetClassCode != null && it.assetClassCode != CASH_CLASS }
            .groupBy { it.type to it.assetClassCode!! }
            .map { (key, items) -> classFlow(key.first.name, key.second, items) }
            .sortedByDescending { it.amount }

    private fun classFlow(direction: String, assetClassCode: String, items: List<NetWorthMovement>): MoneyFlowMovementClass {
        val (described, undescribed) = items.partition { !it.description.isNullOrBlank() }
        val byDescription = described.groupBy { it.description!!.trim() }
            .map { (text, ms) -> MoneyFlowMovementDescription(text, Money.normalize(ms.sumOf { it.amount }), ms.size) }
            .sortedByDescending { it.amount }
        return MoneyFlowMovementClass(
            nodeId = classNodeId(direction, assetClassCode),
            assetClassCode = assetClassCode,
            direction = direction,
            amount = Money.normalize(items.sumOf { it.amount }),
            movementCount = items.size,
            descriptions = byDescription.take(TOP_DESCRIPTIONS),
            moreDescriptions = (byDescription.size - TOP_DESCRIPTIONS).coerceAtLeast(0),
            undescribedCount = undescribed.size,
            undescribedAmount = Money.normalize(undescribed.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }),
        )
    }

    private companion object {
        const val TOP_DESCRIPTIONS = 3
    }
}
