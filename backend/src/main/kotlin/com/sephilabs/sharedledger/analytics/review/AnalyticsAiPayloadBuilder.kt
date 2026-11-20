package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.ai.AiJsonUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Builds the canonical, strictly sanitized JSON payload sent to the AI writer (and shown verbatim
 *  in the owner-only payload preview). Contains only the deterministically selected summary facts,
 *  selected findings with typed placeholder figures, and compact sanitized aggregates for follow-up Q&A. */
object AnalyticsAiPayloadBuilder {

    private val mapper = JsonMapper.builder().build()

    data class BuiltPayload(
        val json: String,
        val tree: ObjectNode,
        val localLabelsJson: String,
        val hasBudgets: Boolean,
        val hasDeficit: Boolean,
        val truncatedSections: List<String> = emptyList(),
    )

    fun build(
        review: SelectedReview,
        locale: String,
        categoryLabel: (String) -> String,
    ): BuiltPayload {
        val root = mapper.createObjectNode()
        val period = review.period
        val javaLocale = if (locale.startsWith("es", ignoreCase = true)) Locale.forLanguageTag("es-ES") else Locale.US
        val monthLabel = Month.of(period.month).getDisplayName(TextStyle.FULL, javaLocale)

        // 1. scope
        root.putObject("scope").apply {
            put("currency", review.currency)
            put("periodYear", period.year)
            put("periodMonth", period.month)
            put("monthName", monthLabel)
            put("reviewKind", period.reviewKind)
            put("daysElapsed", period.daysElapsed)
            put("daysInMonth", period.daysInMonth)
            put("monthsAvailable", period.monthsAvailable)
            put("hasBudgets", review.hasBudgets)
        }

        // 2. summaryFacts (null in savings_only mode)
        review.summaryFacts?.let { sf ->
            root.putObject("summaryFacts").apply {
                put("expensesDirection", sf.expensesDirection)
                put("incomeDirection", sf.incomeDirection)
                put("savingsDirection", sf.savingsDirection)
                put("diagnosis", sf.diagnosis.name)
                put("diagnosisDetail", sf.diagnosisDetail)
                put("hasDeficit", sf.hasDeficit)
                put("hasProjectedDeficit", sf.hasProjectedDeficit)
                putObject("figures").apply {
                    for (slot in sf.figures) {
                        putObject(slot.key).apply {
                            put("role", slot.role)
                            put("kind", slot.kind.name.lowercase())
                            putNumber("value", slot.value)
                        }
                    }
                }
            }
        } ?: root.putNull("summaryFacts")

        // 3. findings (pre-selected, strictly ordered, strictly sanitized — never emits mergedVariants or real P2P names)
        root.putArray("findings").apply {
            for (f in review.allFindings) {
                addObject().apply {
                    put("id", f.id)
                    put("section", f.section.name)
                    put("kind", f.kind.name)
                    if (f.anonymized) {
                        put("subject", f.payloadSubject)
                        put("anonymized", true)
                    } else if (f.payloadSubject != f.categoryCode) {
                        put("subject", f.payloadSubject)
                    }
                    f.categoryCode?.let { code ->
                        put("categoryCode", code)
                        put("categoryName", categoryLabel(code))
                    }
                    f.action?.let { put("action", it.name) }
                    if (f.context.isNotEmpty()) {
                        putObject("context").apply {
                            for ((k, v) in f.context) put(k, v)
                        }
                    }
                    putObject("figures").apply {
                        for (slot in f.figures) {
                            putObject(slot.key).apply {
                                put("role", slot.role)
                                put("kind", slot.kind.name.lowercase())
                                putNumber("value", slot.value)
                            }
                        }
                    }
                }
            }
        }

        // 4. Compact sanitized aggregates for follow-up Q&A grounding
        if (review.unchangedEssentialRecurringCount > 0) {
            root.putObject("unchangedEssentialRecurring").apply {
                put("count", review.unchangedEssentialRecurringCount)
                putNumber("monthlyTotal", review.unchangedEssentialRecurringMonthly)
            }
        }
        if (review.otherMerchants.purchases > 0) {
            root.putObject("otherMerchants").apply {
                putNumber("total", review.otherMerchants.total)
                put("purchases", review.otherMerchants.purchases)
                put("merchantCount", review.otherMerchants.merchantCount)
            }
        }
        if (review.cutCombinations.isNotEmpty()) {
            root.putArray("cutCombinations").apply {
                for (c in review.cutCombinations) {
                    addObject().apply {
                        put("targetMonthly", c.targetMonthly)
                        put("reached", c.reached)
                        putNumber("combinedMonthlySaving", c.combinedMonthlySaving)
                        putNumber("combinedAnnualSaving", c.combinedAnnualSaving)
                    }
                }
            }
        }

        val json = AiJsonUtils.canonicalJson(root)
        val localLabelsJson = buildLocalMetaJson(review)
        val hasDeficit = review.summaryFacts?.let { it.hasDeficit || it.hasProjectedDeficit } ?: false

        return BuiltPayload(
            json = json,
            tree = root,
            localLabelsJson = localLabelsJson,
            hasBudgets = review.hasBudgets,
            hasDeficit = hasDeficit,
            truncatedSections = emptyList(),
        )
    }

    private fun buildLocalMetaJson(review: SelectedReview): String {
        val root = mapper.createObjectNode()
        root.put("savingsOmitted", review.savingsOmitted)
        root.put("whatChangedOmitted", review.whatChangedOmitted)
        root.put("budgetsOmitted", review.budgetsOmitted)

        val findingsObj = root.putObject("findings")
        for (f in review.allFindings) {
            findingsObj.putObject(f.id).apply {
                put("id", f.id)
                put("section", f.section.name)
                put("kind", f.kind.name)
                f.candidateId?.let { put("candidateId", it) }
                f.localSubject?.let { put("localName", it) }
                put("anonymized", f.anonymized)
                if (f.mergedVariants.isNotEmpty()) {
                    putArray("mergedVariants").apply { f.mergedVariants.forEach { add(it) } }
                }
                f.categoryCode?.let { put("categoryCode", it) }
                f.groupCode?.let { put("groupCode", it) }
                f.action?.let { put("action", it.name) }
                put("severity", f.severity)
                f.changeKind?.let { put("changeKind", it) }
                f.potentialMonthlySaving?.let { put("potentialMonthlySaving", it.toPlainString()) }
                f.potentialAnnualSaving?.let { put("potentialAnnualSaving", it.toPlainString()) }
                putArray("metrics").apply {
                    for (slot in f.figures) {
                        addObject().apply {
                            put("key", slot.role)
                            put("kind", slot.kind.name.lowercase())
                            put("value", slot.value.stripTrailingZeros().toPlainString())
                        }
                    }
                }
                f.txFilter?.let { set("txFilter", mapper.valueToTree<JsonNode>(it)) }
                f.explorerLink?.let { set("explorerLink", mapper.valueToTree<JsonNode>(it)) }
                f.simulationScenario?.let { set("simulationScenario", mapper.valueToTree<JsonNode>(it.toScenarioDto())) }
            }
        }
        return mapper.writeValueAsString(root)
    }

    private fun ObjectNode.putNumber(field: String, value: BigDecimal) {
        val norm = value.setScale(2, RoundingMode.HALF_EVEN)
        put(field, norm.toDouble())
    }
}
