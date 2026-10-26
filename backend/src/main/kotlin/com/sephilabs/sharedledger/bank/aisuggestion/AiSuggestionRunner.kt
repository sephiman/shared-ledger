package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.bank.CategorizationService
import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.transaction.Direction
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

const val AI_SUGGESTION_MIN_CONFIDENCE = 0.85

/** Sends a run's candidates to the model in sequential batches and stores what clears the threshold. A failed
 *  batch is counted and logged and the next one still runs; only an unexpected error fails the whole run. */
@Component
class AiSuggestionRunner(
    private val model: SuggestionModel,
    private val pending: PendingMovementRepository,
    private val runs: AiSuggestionRunRepository,
    private val categories: CategoryService,
    private val categorization: CategorizationService,
    private val messages: Messages,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    fun execute(runId: UUID, householdId: UUID, candidateIds: List<UUID>) {
        // Tag every line of this run, the client's retries included. Restored after, not cleared: under backlog
        // the executor runs this on the caller's request thread, whose own MDC must survive.
        val previous = MDC.getCopyOfContextMap()
        MDC.put("householdId", householdId.toString())
        MDC.put("aiRunId", runId.toString())
        try {
            val catalogue = catalogue(householdId)
            val rules = ruleExamples(householdId)
            val system = CategorySuggestionPrompt.system(catalogue, rules)
            val directionByCode = catalogue.associate { it.code to it.direction }
            val sessionId = UUID.randomUUID()
            val batches = candidateIds.chunked(AI_SUGGESTION_BATCH_SIZE)
            log.info(
                "ai_suggestion_run_prompt run={} household={} session={} categories={} rules={} rule_examples={} system_chars={}",
                runId, householdId, sessionId, catalogue.size, rules.size,
                CategorySuggestionPrompt.sampleRules(rules).size, system.length,
            )
            batches.forEachIndexed { index, ids ->
                runBatch(runId, householdId, "${index + 1}/${batches.size}", ids, system, directionByCode, sessionId)
            }
            val run = finish(runId, AiSuggestionRunStatus.completed)
            log.info(
                "ai_suggestion_run run={} household={} user={} status={} prompt={} model={} items_sent={} stored={} " +
                    "unsuggested={} batches={} batches_failed={} input_tokens={} output_tokens={}",
                run.id, householdId, run.userId, run.status, run.promptVersion, run.model, run.itemsSent,
                run.suggestionsStored, run.itemsUnsuggested, run.batchesTotal, run.batchesFailed, run.inputTokens,
                run.outputTokens,
            )
        } catch (ex: Exception) {
            log.error("ai_suggestion_run run={} household={} status=failed", runId, householdId, ex)
            finish(runId, AiSuggestionRunStatus.failed)
        } finally {
            if (previous != null) MDC.setContextMap(previous) else MDC.clear()
        }
    }

    private fun runBatch(
        runId: UUID,
        householdId: UUID,
        label: String,
        ids: List<UUID>,
        system: String,
        directionByCode: Map<String, Direction>,
        sessionId: UUID,
    ) {
        // Re-read: a confirm, a reject or Apply rules since the press takes an item out of the run.
        val byId = pending.findAllByIdInAndHouseholdId(ids, householdId).associateBy { it.id }
        val items = ids.mapNotNull { byId[it] }.filter { it.isAiCandidate() }
        if (items.isEmpty()) {
            log.info("ai_suggestion_batch run={} household={} batch={} items=0 outcome=skipped reason=all_resolved", runId, householdId, label)
            record(runId) { it.batchesDone++ }
            return
        }
        val promptIds = items.mapIndexed { i, item -> "m${i + 1}" to item }.toMap()
        val prompt = ChatPrompt(
            system = system,
            user = CategorySuggestionPrompt.user(promptIds.map { (id, item) -> item.toPromptMovement(id) }),
            sessionId = sessionId,
        )
        log.info(
            "ai_suggestion_batch_sent run={} household={} batch={} items={} resolved_since_press={} user_chars={}",
            runId, householdId, label, items.size, ids.size - items.size, prompt.user.length,
        )
        val answer = try {
            model.complete(prompt)
        } catch (ex: SuggestionModelException) {
            log.warn(
                "ai_suggestion_batch run={} household={} batch={} items={} attempts={} outcome=failed error={}",
                runId, householdId, label, items.size, ex.attempts, ex.message,
            )
            record(runId) { it.batchesDone++; it.batchesFailed++; it.itemsSent += items.size }
            return
        }
        val parsed = CategorySuggestionParser.parse(
            answer.content,
            promptIds.mapValues { (_, item) -> item.direction },
            directionByCode,
        )
        val outcome = BatchOutcome()
        record(runId) { run ->
            for ((promptId, item) in promptIds) outcome.add(store(householdId, item, parsed.getValue(promptId)))
            run.batchesDone++
            run.itemsSent += items.size
            run.suggestionsStored += outcome.stored
            run.itemsUnsuggested += outcome.belowThreshold + outcome.invalid.values.sum()
            run.inputTokens += answer.inputTokens ?: 0
            run.outputTokens += answer.outputTokens ?: 0
        }
        log.info(
            "ai_suggestion_batch run={} household={} batch={} items={} attempts={} latency_ms={} input_tokens={} " +
                "output_tokens={} reasoning_tokens={} stored={} below_threshold={} invalid={} skipped={} outcome=ok",
            runId, householdId, label, items.size, answer.attempts, answer.latencyMs, answer.inputTokens,
            answer.outputTokens, answer.reasoningTokens, outcome.stored, outcome.belowThreshold, outcome.invalid,
            outcome.skipped,
        )
    }

    private fun store(householdId: UUID, item: PendingMovement, suggestion: ParsedSuggestion): StoreResult {
        if (suggestion is ParsedSuggestion.Invalid) return StoreResult.Invalid(suggestion.reason)
        suggestion as ParsedSuggestion.Valid
        if (suggestion.confidence < AI_SUGGESTION_MIN_CONFIDENCE) return StoreResult.BelowThreshold
        // Down, so a stored 0.85 never stands for a model answer that was below it.
        val confidence = BigDecimal.valueOf(suggestion.confidence).setScale(2, RoundingMode.DOWN)
        val updated = pending.storeAiSuggestion(item.id, householdId, suggestion.categoryCode, confidence)
        return if (updated == 1) StoreResult.Stored else StoreResult.Skipped
    }

    private fun record(runId: UUID, change: (AiSuggestionRun) -> Unit) {
        tx.executeWithoutResult { change(runs.findById(runId).orElseThrow()) }
    }

    private fun finish(runId: UUID, status: AiSuggestionRunStatus): AiSuggestionRun =
        tx.execute {
            runs.findById(runId).orElseThrow().apply {
                this.status = status
                finishedAt = Instant.now()
            }
        }!!

    private fun catalogue(householdId: UUID): List<CatalogueEntry> =
        categories.listForHousehold(householdId).map {
            CatalogueEntry(
                code = it.code,
                // Global names are i18n keys; the prompt is in English whatever the members read.
                name = if (it.custom) it.name else messages.resolve(it.name, fallback = it.code, locale = Locale.ENGLISH),
                group = it.group,
                direction = Direction.valueOf(it.kind),
                essential = it.essential,
            )
        }

    private fun ruleExamples(householdId: UUID): List<RuleExample> =
        categorization.orderedRules(householdId).map {
            RuleExample(it.matchField, it.matchOp, it.matchValue, it.direction, it.categoryCode)
        }

    private fun PendingMovement.isAiCandidate() =
        status == MovementStatus.pending && suggestedCategoryCode.isNullOrBlank()

    private fun PendingMovement.toPromptMovement(promptId: String) = PromptMovement(
        id = promptId,
        counterparty = counterparty,
        description = description,
        direction = direction,
        bookingMonth = YearMonth.from(bookingDate),
    )

    private sealed interface StoreResult {
        data object Stored : StoreResult
        data object BelowThreshold : StoreResult
        data class Invalid(val reason: InvalidReason) : StoreResult
        // Resolved by someone else between the read and the write; neither a suggestion nor a miss.
        data object Skipped : StoreResult
    }

    private class BatchOutcome {
        var stored = 0
        var belowThreshold = 0
        var skipped = 0
        val invalid = mutableMapOf<InvalidReason, Int>()

        fun add(result: StoreResult) {
            when (result) {
                StoreResult.Stored -> stored++
                StoreResult.BelowThreshold -> belowThreshold++
                StoreResult.Skipped -> skipped++
                is StoreResult.Invalid -> invalid.merge(result.reason, 1, Int::plus)
            }
        }
    }
}
