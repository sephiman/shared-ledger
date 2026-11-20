package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.ai.AiJsonUtils
import com.sephilabs.sharedledger.ai.AiRunStatus
import com.sephilabs.sharedledger.bank.aisuggestion.ChatAnswer
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.catalog.CategoryService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Runs one Analytics AI spending review off the HTTP thread: asks the model to write placeholder prose
 *  over the pre-selected findings, renders every figure through the app's own formatters, falls back
 *  per finding to localized templates on any omission/violation, and persists the complete result. */
@Component
class AnalyticsAiReviewRunner(
    private val model: SuggestionModel,
    private val runs: AnalyticsAiReviewRunRepository,
    private val categoryService: CategoryService,
    private val skeletonService: SpendingReviewSkeletonService,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val mapper = JsonMapper.builder().build()

    fun execute(runId: UUID) {
        val previous = MDC.getCopyOfContextMap()
        MDC.put("analyticsAiRunId", runId.toString())
        try {
            val run = runs.findById(runId).orElse(null) ?: return
            MDC.put("householdId", run.householdId.toString())
            val payload = mapper.readTree(run.payloadJson)
            val localLabels = runCatching { mapper.readTree(run.localLabelsJson) }.getOrNull()
            val canonicalPayloadJson = AiJsonUtils.canonicalJson(payload)
            val categories = categoryService.listForHousehold(run.householdId)
            val labelFn = skeletonService.categoryLabelResolver(categories, run.locale)

            val prompt = ChatPrompt(
                system = AnalyticsAiReviewPrompt.system(run.locale),
                user = AnalyticsAiReviewPrompt.user(canonicalPayloadJson),
                sessionId = UUID.randomUUID(),
            )
            log.info(
                "analytics_ai_review_sent run={} household={} session={} period={}-{} kind={} system_chars={} user_chars={}",
                runId, run.householdId, prompt.sessionId, run.periodYear, run.periodMonth, run.reviewKind,
                prompt.system.length, prompt.user.length,
            )

            val answer = try {
                model.complete(prompt)
            } catch (ex: SuggestionModelException) {
                log.warn(
                    "analytics_ai_review_run run={} household={} attempts={} outcome=failed error={}",
                    runId, run.householdId, ex.attempts, ex.message,
                )
                finish(runId, AiRunStatus.failed, errorCode = "MODEL_ERROR")
                return
            }

            val parsed = JsonObjectReader.first(answer.content)
            if (parsed == null || !isWellFormed(parsed)) {
                log.warn("analytics_ai_review_run run={} household={} outcome=failed error=unreadable_answer", runId, run.householdId)
                finish(runId, AiRunStatus.failed, errorCode = "UNREADABLE_ANSWER", answer = answer)
                return
            }

            val rendered = SpendingReviewRenderer.renderFromPersistedSnapshot(
                payload = payload,
                localLabels = localLabels,
                modelOutput = parsed,
                locale = run.locale,
                categoryLabel = labelFn,
            )

            val resultNode = mapper.createObjectNode().apply {
                if (rendered.summary != null) put("summary", rendered.summary) else putNull("summary")
                put("summaryUsedModel", rendered.summaryUsedModel)
                rendered.summaryFallbackReason?.let { put("summaryFallbackReason", it) }
                put("modelWrittenCount", rendered.modelWrittenCount)
                put("templateFallbackCount", rendered.templateFallbackCount)
                putArray("findings").apply {
                    for (f in rendered.findings) {
                        addObject().apply {
                            put("id", f.findingId)
                            put("title", f.title)
                            put("detail", f.detail)
                            put("usedModel", f.usedModel)
                            f.fallbackReason?.let { put("fallbackReason", it) }
                        }
                    }
                }
            }

            val fallbackSummary = rendered.findings
                .filter { !it.usedModel }
                .associate { it.findingId to (it.fallbackReason ?: "unknown") }

            val completed = finish(runId, AiRunStatus.completed, answer = answer) {
                it.resultJson = mapper.writeValueAsString(resultNode)
                it.findingsDropped = 0
                it.citationsTotal = 0
                it.citationsUngrounded = 0
            }
            log.info(
                "analytics_ai_review_run run={} household={} user={} status={} prompt={} model={} period={}-{} kind={} " +
                    "model_written={} template_fallback={} summary_fallback={} finding_fallbacks={} " +
                    "attempts={} latency_ms={} input_tokens={} output_tokens={} reasoning_tokens={}",
                runId, run.householdId, run.userId, completed?.status, run.promptVersion, run.model,
                run.periodYear, run.periodMonth, run.reviewKind,
                rendered.modelWrittenCount, rendered.templateFallbackCount,
                rendered.summaryFallbackReason ?: "none", fallbackSummary,
                answer.attempts, answer.latencyMs,
                answer.inputTokens, answer.outputTokens, answer.reasoningTokens,
            )
        } catch (ex: Exception) {
            log.error("analytics_ai_review_run run={} status=failed", runId, ex)
            finish(runId, AiRunStatus.failed, errorCode = "INTERNAL_ERROR")
        } finally {
            if (previous != null) MDC.setContextMap(previous) else MDC.clear()
        }
    }

    private fun isWellFormed(parsed: JsonNode): Boolean =
        parsed.isObject && (parsed.path("findings").isArray || parsed.path("summary").isString)

    private fun finish(
        runId: UUID,
        status: AiRunStatus,
        errorCode: String? = null,
        answer: ChatAnswer? = null,
        update: (AnalyticsAiReviewRun) -> Unit = {},
    ) = tx.execute {
        runs.findById(runId).orElse(null)?.apply {
            update(this)
            errorCode?.let { this.errorCode = it }
            inputTokens = answer?.inputTokens
            outputTokens = answer?.outputTokens
            reasoningTokens = answer?.reasoningTokens
            latencyMs = answer?.latencyMs
            this.status = status
            finishedAt = Instant.now()
        }
    }
}
