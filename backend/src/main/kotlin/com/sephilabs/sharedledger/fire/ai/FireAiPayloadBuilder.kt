package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.fire.FireProjectionResponse
import com.sephilabs.sharedledger.fire.FireScenarioOutput
import com.sephilabs.sharedledger.fire.FireTierKey
import com.sephilabs.sharedledger.fire.history.FireHistoryResponse
import com.sephilabs.sharedledger.fire.levers.FireLeversResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/** Builds the compact, pre-computed FIRE snapshot sent to the AI plan review. No DB IDs, user emails,
 *  account names, or raw transactions ever enter this payload. */
object FireAiPayloadBuilder {

    private val mapper = JsonMapper.builder().build()

    data class BuiltPayload(
        val json: String,
        val tree: JsonNode,
        val referenceTier: FireTierKey,
        val referenceScenarioIndex: Int,
        val referenceScenarioName: String,
        val coveragePercent: Double?,
        val estimatedYear: Int?,
        val probability: Double?,
    )

    fun build(
        currency: String,
        projection: FireProjectionResponse,
        history: FireHistoryResponse,
        levers: FireLeversResponse,
    ): BuiltPayload {
        val refTier = resolveReferenceTier(projection)
        val refIdx = projection.settings.referenceScenarioIndex.coerceIn(0, (projection.scenarios.size - 1).coerceAtLeast(0))
        val refScenario = projection.scenarios.getOrNull(refIdx)
        val refScenarioName = resolveScenarioName(projection.scenarios, refIdx)
        val refTierOutput = projection.tiers.firstOrNull { it.key == refTier }
        val refStat = refScenario?.tierStats?.firstOrNull { it.tier == refTier }

        val root = mapper.createObjectNode()
        root.putObject("scope").apply {
            put("currency", currency)
            put("referenceTier", refTier.name)
            put("referenceScenarioIndex", refIdx)
            put("referenceScenarioName", refScenarioName)
            put("startYear", projection.startYear)
            put("targetYear", projection.settings.targetYear.toInt())
            put("snapshotDate", projection.snapshotDate?.toString())
            put("monteCarloTrials", projection.monteCarloTrials)
        }

        root.putObject("headline").apply {
            putNumber("startingValue", projection.startingValue)
            put("targetYear", projection.settings.targetYear.toInt())
            putNumber("expectedInflationPct", projection.settings.expectedInflationPct)
            putNumber("safeWithdrawalRatePct", projection.settings.safeWithdrawalRatePct)
            put("drawdownYears", projection.settings.drawdownYears.toInt())
            put("applyCapitalGainsTax", projection.settings.applyCapitalGainsTax)
            put("indexTaxBrackets", projection.settings.indexTaxBrackets)
            put("indexContribution", projection.settings.indexContribution)
            putNullableNumber("targetToday", refTierOutput?.targetToday)
            putNullableDouble("coveragePercent", refTierOutput?.coveragePercent)
            putNullableNumber("monthlyNetSpending", refTierOutput?.monthlyNetSpending)
            putNullableNumber("annualNetSpending", refTierOutput?.annualNetSpending)
            putNullableNumber("annualGrossSpending", refTierOutput?.annualGrossSpending)
            putNullableNumber("estimatedAnnualTax", refTierOutput?.estimatedAnnualTax)
            putNullableInt("deterministicHitYear", refStat?.deterministicHitYear)
            putNullableDouble("probabilityOfReachingTargetPct", refStat?.probabilityOfReachingTarget?.times(100.0))
            putNullableInt("estimatedYear", refStat?.estimatedYear)
            putNullableInt("medianYearAmongReaching", refStat?.medianYearAmongReaching)
            putNullableDouble("coastProbabilityPct", refStat?.coastProbabilityOfReachingTarget?.times(100.0))
            putNullableInt("coastEstimatedYear", refStat?.coastEstimatedYear)
            putNullableNumber("coastTargetToday", refStat?.coastTargetToday)
            putNullableNumber("requiredMonthlyToHitTarget", refStat?.requiredMonthlyToHitTarget)
            putNullableDouble("drawdownSurvivalRatePct", refStat?.drawdownSurvivalRate?.times(100.0))

            if (refStat != null && refStat.drawdownRealPercentiles.isNotEmpty()) {
                val milestones = listOfNotNull(
                    refStat.drawdownRealPercentiles.firstOrNull(),
                    refStat.drawdownRealPercentiles.getOrNull(refStat.drawdownRealPercentiles.size / 2)
                        ?.takeIf { refStat.drawdownRealPercentiles.size > 2 },
                    refStat.drawdownRealPercentiles.lastOrNull()
                        ?.takeIf { refStat.drawdownRealPercentiles.size > 1 },
                )
                putArray("drawdownRealMilestones").apply {
                    for (m in milestones) {
                        addObject().apply {
                            put("retirementYear", m.year)
                            putNumber("p10", m.p10)
                            putNumber("p50", m.p50)
                            putNumber("p90", m.p90)
                        }
                    }
                }
            }
        }

        root.putArray("tiers").apply {
            for (t in projection.tiers.filter { it.enabled }) {
                val stat = refScenario?.tierStats?.firstOrNull { it.tier == t.key }
                addObject().apply {
                    put("key", t.key.name)
                    putNullableNumber("targetToday", t.targetToday)
                    putNullableDouble("coveragePercent", t.coveragePercent)
                    putNullableNumber("monthlyNetSpending", t.monthlyNetSpending)
                    putNullableNumber("estimatedAnnualTax", t.estimatedAnnualTax)
                    putNullableDouble("probabilityOfReachingTargetPct", stat?.probabilityOfReachingTarget?.times(100.0))
                    putNullableInt("estimatedYear", stat?.estimatedYear)
                    putNullableNumber("coastTargetToday", stat?.coastTargetToday)
                    putNullableDouble("drawdownSurvivalRatePct", stat?.drawdownSurvivalRate?.times(100.0))
                }
            }
        }

        root.putArray("scenarios").apply {
            for ((idx, s) in projection.scenarios.withIndex()) {
                val stat = s.tierStats.firstOrNull { it.tier == refTier }
                val horizonP = s.percentiles.lastOrNull()
                addObject().apply {
                    put("index", idx)
                    put("name", resolveScenarioName(projection.scenarios, idx))
                    put("isReference", idx == refIdx)
                    putNumber("meanPercent", s.meanPercent)
                    putNumber("stdDevPercent", s.stdDevPercent)
                    put("historical", s.historical)
                    putNullableDouble("probabilityOfReachingTargetPct", stat?.probabilityOfReachingTarget?.times(100.0))
                    putNullableInt("estimatedYear", stat?.estimatedYear)
                    putNullableDouble("drawdownSurvivalRatePct", stat?.drawdownSurvivalRate?.times(100.0))
                    if (horizonP != null) {
                        put("horizonYear", horizonP.year)
                        putNumber("horizonNominalP10", horizonP.p10)
                        putNumber("horizonNominalP50", horizonP.p50)
                        putNumber("horizonNominalP90", horizonP.p90)
                    }
                }
            }
        }

        root.putObject("assumptionsVsReality").apply {
            putObject("spending").apply {
                putNumber("essentialMonthly", projection.spending.essentialMonthly)
                putNumber("totalMonthly", projection.spending.totalMonthly)
                putNumber("derivedEssentialMonthly", projection.spending.derivedEssentialMonthly)
                putNumber("derivedTotalMonthly", projection.spending.derivedTotalMonthly)
                put("essentialMode", projection.spending.essentialMode.name)
                put("totalMode", projection.spending.totalMode.name)
                put("monthsAvailable", projection.spending.monthsAvailable)
                put("lookbackMonths", projection.spending.lookbackMonths)
                put("aggregation", projection.spending.aggregation.name)
                put("excludeCurrentMonth", projection.spending.excludeCurrentMonth)
                put("noSpendingSince", projection.spending.noSpendingSince?.toString())
            }
            putObject("contributions").apply {
                put("mode", projection.contributions.mode.name)
                putNullableNumber("activeMonthly", projection.contributions.activeMonthly)
                putNumber("manualMonthly", projection.contributions.manualMonthly)
                putNullableNumber("savingsMonthly", projection.contributions.savingsMonthly)
                putNullableDouble("savingsRatePct", projection.contributions.savingsRatePct)
                putNullableNumber("movementsMonthly", projection.contributions.movementsMonthly)
            }
            putObject("returns").apply {
                putNullableNumber("referenceMeanPercent", refScenario?.meanPercent)
                putNullableNumber("referenceStdDevPercent", refScenario?.stdDevPercent)
                projection.suggestedScenario?.let { s ->
                    putObject("suggestedFromAssetMix").apply {
                        putNumber("meanPercent", s.meanPercent)
                        putNumber("stdDevPercent", s.stdDevPercent)
                    }
                } ?: putNull("suggestedFromAssetMix")
                projection.historicalScenario?.let { h ->
                    putObject("householdHistorical").apply {
                        putNumber("meanPercent", h.meanPercent)
                        putNumber("stdDevPercent", h.stdDevPercent)
                        put("yearsOfData", h.yearsOfData)
                    }
                } ?: putNull("householdHistorical")
                projection.actualReturn?.let { a ->
                    putObject("actualXirr").apply {
                        putNumber("annualizedPercent", a.annualizedPercent)
                        put("fromDate", a.fromDate.toString())
                        put("toDate", a.toDate.toString())
                        put("movementCount", a.movementCount)
                        put("uncoveredMonths", a.uncoveredMonths)
                    }
                } ?: putNull("actualXirr")
                put("actualReturnUnavailableReason", projection.actualReturnUnavailableReason?.name)
            }
            putObject("taxAndPrincipal").apply {
                putNumber("gainFractionPct", projection.gainFraction.percent)
                put("gainFractionSource", projection.gainFraction.source)
                put("gainFractionUncoveredMonths", projection.gainFraction.uncoveredMonths)
                put("taxBracketsCount", projection.settings.taxBrackets.size)
            }
        }

        root.putArray("cashFlowEvents").apply {
            for (e in projection.settings.cashFlowEvents) {
                addObject().apply {
                    put("year", e.year)
                    put("kind", e.kind.name)
                    putNumber("amount", e.amount)
                    put("label", e.label)
                    put("template", e.template)
                }
            }
        }

        root.putObject("history").apply {
            put("pointCount", history.points.size)
            put("manualContribution", history.manualContribution)
            put("manualSpending", history.manualSpending)
            val first = history.points.firstOrNull()
            val latest = history.points.lastOrNull()
            if (first != null) {
                putObject("earliestPoint").apply {
                    put("date", first.date.toString())
                    put("coveragePercent", first.coveragePercent)
                    putNullableInt("estimatedYear", first.estimatedYear)
                    putNullableDouble("probabilityPct", first.probability?.times(100.0))
                    putNumber("wealth", first.wealth)
                    putNumber("target", first.target)
                }
            } else {
                putNull("earliestPoint")
            }
            if (latest != null) {
                putObject("latestPoint").apply {
                    put("date", latest.date.toString())
                    put("coveragePercent", latest.coveragePercent)
                    putNullableInt("estimatedYear", latest.estimatedYear)
                    putNullableDouble("probabilityPct", latest.probability?.times(100.0))
                    putNumber("wealth", latest.wealth)
                    putNumber("target", latest.target)
                }
            } else {
                putNull("latestPoint")
            }
            putArray("omitted").apply {
                for (o in history.omitted) {
                    addObject().apply {
                        put("reason", o.reason.name)
                        put("count", o.count)
                    }
                }
            }
            history.attribution?.let { attr ->
                putObject("attribution").apply {
                    put("fromDate", attr.fromDate.toString())
                    put("toDate", attr.toDate.toString())
                    put("merged", attr.merged)
                    putNumber("totalPoints", attr.totalPoints)
                    putNullableNumber("contributedPoints", attr.contributedPoints)
                    putNullableNumber("marketPoints", attr.marketPoints)
                    putNumber("wealthPoints", attr.wealthPoints)
                    putNumber("needPoints", attr.needPoints)
                    putNullableNumber("contributedAmount", attr.contributedAmount)
                    putNullableNumber("marketAmount", attr.marketAmount)
                    putNumber("wealthFrom", attr.wealthFrom)
                    putNumber("wealthTo", attr.wealthTo)
                    putNumber("targetFrom", attr.targetFrom)
                    putNumber("targetTo", attr.targetTo)
                }
            } ?: putNull("attribution")
        }

        root.putArray("levers").apply {
            for (row in levers.rows) {
                addObject().apply {
                    put("key", row.key.name)
                    put("outOfYourHands", row.outOfYourHands)
                    put("applicable", row.applicable)
                    putNullableInt("estimatedYear", row.estimatedYear)
                    putNullableDouble("probabilityPct", row.probability?.times(100.0))
                    putNullableInt("yearChange", row.yearChange)
                    putNullableDouble("probabilityChangePoints", row.probabilityChangePoints)
                    putNullableNumber("amount", row.amount)
                    putNullableNumber("percent", row.percent)
                    putNullableNumber("withdrawalFromPct", row.withdrawalFromPct)
                    putNullableNumber("withdrawalToPct", row.withdrawalToPct)
                }
            }
        }

        return BuiltPayload(
            json = mapper.writeValueAsString(root),
            tree = root,
            referenceTier = refTier,
            referenceScenarioIndex = refIdx,
            referenceScenarioName = refScenarioName,
            coveragePercent = refTierOutput?.coveragePercent,
            estimatedYear = refStat?.estimatedYear,
            probability = refStat?.probabilityOfReachingTarget,
        )
    }

    private fun resolveReferenceTier(projection: FireProjectionResponse): FireTierKey {
        val wanted = projection.settings.referenceTier
        val enabled = projection.tiers.filter { it.enabled }
        if (enabled.any { it.key == wanted }) return wanted
        if (enabled.any { it.key == FireTierKey.fire }) return FireTierKey.fire
        return enabled.firstOrNull()?.key ?: FireTierKey.fire
    }

    private fun resolveScenarioName(scenarios: List<FireScenarioOutput>, idx: Int): String {
        val own = scenarios.getOrNull(idx)?.name?.trim()
        if (!own.isNullOrEmpty()) return own
        val rank = scenarios.indices
            .sortedWith(compareBy({ scenarios[it].meanPercent }, { it }))
            .indexOf(idx) + 1
        return when (rank) {
            1 -> "Conservative"
            2 -> "Base"
            3 -> "Optimistic"
            else -> "Scenario $rank"
        }
    }

    private fun ObjectNode.putNumber(field: String, value: BigDecimal) {
        put(field, value.toDouble())
    }

    private fun ObjectNode.putNullableNumber(field: String, value: BigDecimal?) {
        if (value == null) putNull(field) else put(field, value.toDouble())
    }

    private fun ObjectNode.putNullableDouble(field: String, value: Double?) {
        if (value == null || !value.isFinite()) putNull(field) else put(field, value)
    }

    private fun ObjectNode.putNullableInt(field: String, value: Int?) {
        if (value == null) putNull(field) else put(field, value)
    }
}
