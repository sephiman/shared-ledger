package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.bank.aisuggestion.ChatAnswer
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.fire.FirePreviewRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Runs one FIRE plan review call off the HTTP thread, checks citations against the snapshot, and records the result. */
@Component
class FireAiAnalysisRunner(
    private val model: SuggestionModel,
    private val runs: FireAiAnalysisRunRepository,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val mapper = JsonMapper.builder().build()

    fun execute(runId: UUID) {
        val previous = MDC.getCopyOfContextMap()
        MDC.put("fireAiRunId", runId.toString())
        try {
            val run = runs.findById(runId).orElse(null) ?: return
            MDC.put("householdId", run.householdId.toString())
            val payload = mapper.readTree(run.payloadJson)
            val startYear = payload.path("scope").path("startYear").takeIf { it.isNumber }?.asInt()
            val priorContext = selectPriorRun(run)?.let(::buildPriorContext)
            val prompt = ChatPrompt(
                system = FirePlanReviewPrompt.system(run.locale),
                user = FirePlanReviewPrompt.user(run.payloadJson, priorContext),
                sessionId = UUID.randomUUID(),
            )
            log.info(
                "fire_ai_analysis_sent run={} household={} session={} tier={} scenario={} system_chars={} user_chars={}",
                runId, run.householdId, prompt.sessionId, run.referenceTier, run.referenceScenarioName,
                prompt.system.length, prompt.user.length,
            )
            val answer = try {
                model.complete(prompt)
            } catch (ex: SuggestionModelException) {
                log.warn(
                    "fire_ai_analysis_run run={} household={} attempts={} outcome=failed error={}",
                    runId, run.householdId, ex.attempts, ex.message,
                )
                finish(runId, FireAiAnalysisRunStatus.failed, errorCode = "MODEL_ERROR")
                return
            }

            val parsed = JsonObjectReader.first(answer.content)
            if (parsed == null || !isWellFormed(parsed)) {
                log.warn("fire_ai_analysis_run run={} household={} outcome=failed error=unreadable_answer", runId, run.householdId)
                finish(runId, FireAiAnalysisRunStatus.failed, errorCode = "UNREADABLE_ANSWER", answer = answer)
                return
            }

            val sanitized = sanitizeSuggestedPreviews(parsed, startYear)
            val grounding = FireAiGroundingVerifier.verify(payload, sanitized)
            val completed = finish(runId, FireAiAnalysisRunStatus.completed, answer = answer) {
                it.resultJson = mapper.writeValueAsString(sanitized)
                it.observationsCount = sanitized.path("observations").size()
                it.dataGapsCount = sanitized.path("dataGaps").size()
                it.citationsTotal = grounding.total
                it.citationsUngrounded = grounding.ungrounded
            }
            log.info(
                "fire_ai_analysis_run run={} household={} user={} status={} prompt={} model={} observations={} " +
                    "data_gaps={} citations={} ungrounded={} attempts={} latency_ms={} " +
                    "input_tokens={} output_tokens={} reasoning_tokens={}",
                runId, run.householdId, run.userId, completed?.status, run.promptVersion, run.model,
                completed?.observationsCount, completed?.dataGapsCount, grounding.total, grounding.ungrounded,
                answer.attempts, answer.latencyMs, answer.inputTokens, answer.outputTokens, answer.reasoningTokens,
            )
        } catch (ex: Exception) {
            log.error("fire_ai_analysis_run run={} status=failed", runId, ex)
            finish(runId, FireAiAnalysisRunStatus.failed, errorCode = "INTERNAL_ERROR")
        } finally {
            if (previous != null) MDC.setContextMap(previous) else MDC.clear()
        }
    }

    private fun selectPriorRun(current: FireAiAnalysisRun): FireAiAnalysisRun? {
        val candidates = runs.findAllByHouseholdIdAndStatusOrderByCreatedAtDesc(
            current.householdId,
            FireAiAnalysisRunStatus.completed,
        ).filter { it.id != current.id }
        return candidates.firstOrNull {
            it.referenceTier == current.referenceTier && it.referenceScenarioIndex == current.referenceScenarioIndex
        } ?: candidates.firstOrNull()
    }

    private fun buildPriorContext(prior: FireAiAnalysisRun): String? {
        val prevResult = prior.resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() } ?: return null
        val prevSummary = prevResult.path("summary").takeIf { it.isString }?.asString()?.trim()
        if (prevSummary.isNullOrBlank()) return null

        val prevPayload = runCatching { mapper.readTree(prior.payloadJson) }.getOrNull()
        val headline = prevPayload?.path("headline")
        val metrics = buildList {
            fun addNum(label: String, node: JsonNode?) {
                if (node != null && node.isNumber) add("$label=${node.asString()}")
            }
            addNum("startingValue", headline?.path("startingValue"))
            addNum("coveragePercent", headline?.path("coveragePercent"))
            addNum("estimatedYear", headline?.path("estimatedYear"))
            addNum("probabilityPct", headline?.path("probabilityOfReachingTargetPct"))
            addNum("drawdownSurvivalPct", headline?.path("drawdownSurvivalRatePct"))
            addNum("totalMonthlySpending", prevPayload?.path("assumptionsVsReality")?.path("spending")?.path("totalMonthly"))
            addNum("activeMonthlyContrib", prevPayload?.path("assumptionsVsReality")?.path("contributions")?.path("activeMonthly"))
            addNum("savingsRatePct", prevPayload?.path("assumptionsVsReality")?.path("contributions")?.path("savingsRatePct"))
        }
        val prevImprovements = prevResult.path("improvements").takeIf { it.isArray }?.toList()
            ?.mapNotNull { it.path("title").takeIf { t -> t.isString }?.asString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()
        return buildString {
            append("Date: ${prior.createdAt.toString().take(10)}")
            append(" | Scope: tier=${prior.referenceTier}, scenario=${prior.referenceScenarioName}")
            if (metrics.isNotEmpty()) append("\nMetrics: ${metrics.joinToString(", ")}")
            append("\nSummary: $prevSummary")
            if (prevImprovements.isNotEmpty()) append("\nPrior improvements: ${prevImprovements.joinToString("; ")}")
        }
    }

    private fun isWellFormed(parsed: JsonNode): Boolean {
        val obs = parsed.path("observations")
        val hasSummary = parsed.path("summary").isString && parsed.path("summary").asString().isNotBlank()
        val hasObservations = obs.isArray && obs.size() > 0 && obs.toList().all {
            it.isObject && it.path("claim").isString && it.path("claim").asString().isNotBlank()
        }
        return hasSummary || hasObservations
    }

    /** Strips invalid preview objects or out-of-range fields so "Preview in What-If" never feeds bad values. */
    private fun sanitizeSuggestedPreviews(parsed: JsonNode, startYear: Int?): JsonNode {
        if (!parsed.isObject) return parsed
        val root = parsed.deepCopy() as ObjectNode
        val items = root.path("suggestedWhatIfs")
        if (!items.isArray) return root
        items.forEach { item ->
            if (!item.isObject) return@forEach
            val obj = item as ObjectNode
            val previewNode = obj.path("preview")
            if (previewNode.isMissingNode || previewNode.isNull) return@forEach
            val sanitized = sanitizePreview(previewNode, startYear)
            if (sanitized == null) obj.putNull("preview") else obj.set("preview", mapper.valueToTree(sanitized))
        }
        return root
    }

    private fun finish(
        runId: UUID,
        status: FireAiAnalysisRunStatus,
        errorCode: String? = null,
        answer: ChatAnswer? = null,
        counts: (FireAiAnalysisRun) -> Unit = {},
    ) = tx.execute {
        runs.findById(runId).orElse(null)?.apply {
            counts(this)
            errorCode?.let { this.errorCode = it }
            inputTokens = answer?.inputTokens
            outputTokens = answer?.outputTokens
            reasoningTokens = answer?.reasoningTokens
            latencyMs = answer?.latencyMs
            this.status = status
            finishedAt = Instant.now()
        }
    }

    companion object {
        fun sanitizePreview(node: JsonNode, startYear: Int?): FirePreviewRequest? {
            if (!node.isObject) return null
            val contrib = decimalField(node.path("monthlyContribution"))
                ?.takeIf { it >= BigDecimal.ZERO }?.let(Money::normalize)
            val spending = decimalField(node.path("monthlySpending"))
                ?.takeIf { it > BigDecimal.ZERO }?.let(Money::normalize)
            val essential = decimalField(node.path("monthlyEssentialSpending"))
                ?.takeIf { it > BigDecimal.ZERO }?.let(Money::normalize)
            val year = intField(node.path("targetYear"))
                ?.takeIf { y -> y in 1900..2200 && (startYear == null || y >= startYear) }
                ?.toShort()
            val swr = decimalField(node.path("safeWithdrawalRatePct"))
                ?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("100") }
            val lumpSum = decimalField(node.path("lumpSum"))
                ?.takeIf { it >= BigDecimal.ZERO }?.let(Money::normalize) ?: BigDecimal.ZERO
            val returnShift = decimalField(node.path("returnShiftPct"))
                ?.takeIf { it >= BigDecimal("-20") && it <= BigDecimal("20") && it.compareTo(BigDecimal.ZERO) != 0 }

            if (contrib == null && spending == null && essential == null && year == null &&
                swr == null && lumpSum.signum() == 0 && returnShift == null
            ) {
                return null
            }
            return FirePreviewRequest(
                monthlyContribution = contrib,
                targetYear = year,
                safeWithdrawalRatePct = swr,
                lumpSum = lumpSum,
                monthlySpending = spending,
                monthlyEssentialSpending = essential,
                returnShiftPct = returnShift,
            )
        }

        private fun decimalField(node: JsonNode): BigDecimal? = when {
            node.isNumber -> node.decimalValue()
            node.isString -> node.asString().trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
            else -> null
        }

        private fun intField(node: JsonNode): Int? = when {
            node.isInt || node.isLong -> node.asInt()
            node.isNumber -> node.doubleValue().takeIf { it == it.toInt().toDouble() }?.toInt()
            node.isString -> node.asString().trim().toIntOrNull()
            else -> null
        }
    }
}
