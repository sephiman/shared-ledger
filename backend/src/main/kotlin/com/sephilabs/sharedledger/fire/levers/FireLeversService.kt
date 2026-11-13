package com.sephilabs.sharedledger.fire.levers

import com.sephilabs.sharedledger.fire.FireSectionCache
import com.sephilabs.sharedledger.fire.FireService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** "What moves your year most": each lever is one sandbox run of the reference tier under the reference scenario. */
@Service
class FireLeversService(
    private val fire: FireService,
    private val cache: FireSectionCache,
) {

    @Transactional(readOnly = true)
    fun levers(householdId: UUID): FireLeversResponse =
        cache.getOrCompute(householdId, SECTION_LEVERS, "", fire.settingsDto(householdId)) { compute(householdId) }

    private fun compute(householdId: UUID): FireLeversResponse {
        val base = fire.referenceReachLive(householdId)
        val rows = fireLevers(base).map { lever ->
            val preview = lever.preview
            val reach = preview?.let { fire.referenceReachLive(householdId, it) }
            val probability = reach?.probability
            val year = reach?.estimatedYear
            FireLeverRow(
                key = lever.key,
                outOfYourHands = lever.outOfYourHands,
                applicable = preview != null,
                preview = preview,
                estimatedYear = year,
                probability = probability,
                yearChange = if (year != null && base.estimatedYear != null) year - base.estimatedYear else null,
                probabilityChangePoints = if (probability != null && base.probability != null) (probability - base.probability) * PERCENT else null,
                amount = lever.amount,
                percent = lever.percent,
                withdrawalFromPct = lever.withdrawalFromPct,
                withdrawalToPct = lever.withdrawalToPct,
            )
        }
        return FireLeversResponse(
            tier = base.tier,
            horizonYear = base.horizonYear,
            baselineYear = base.estimatedYear,
            baselineProbability = base.probability,
            rows = rows.sortedWith(
                compareByDescending<FireLeverRow> { it.probabilityChangePoints ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.yearChange ?: 0 },
            ),
        )
    }

    private companion object {
        const val SECTION_LEVERS = "levers"
        const val PERCENT = 100.0
    }
}
