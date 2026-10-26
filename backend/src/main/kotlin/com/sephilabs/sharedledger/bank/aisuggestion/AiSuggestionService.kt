package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

const val AI_SUGGESTION_BATCH_SIZE = 25

/** Starts and reports "Suggest with AI" runs. The provider conversation itself runs off the request thread in
 *  [AiSuggestionRunner]; the inbox polls [status] for progress. */
@Service
class AiSuggestionService(
    private val availability: AiAvailability,
    private val settings: AiSuggestionSettingsService,
    private val pending: PendingMovementRepository,
    private val runs: AiSuggestionRunRepository,
    private val runner: AiSuggestionRunner,
    @Qualifier("aiSuggestionExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun status(householdId: UUID): AiSuggestionStatusDto {
        availability.requireConfigured()
        return AiSuggestionStatusDto(
            candidates = pending.countAiCandidates(householdId),
            run = runs.findFirstByHouseholdIdOrderByStartedAtDesc(householdId)?.toDto(),
        )
    }

    /** Snapshots the candidates — pending items with no suggestion of any origin, whatever the inbox shows —
     *  and hands them to the runner. Not transactional: the run row must be committed before the worker reads it. */
    fun start(householdId: UUID, by: User): AiSuggestionRunDto {
        val provider = availability.requireConfigured()
        if (!settings.isEnabled(householdId)) throw rejected(householdId, by, "AI_SUGGESTIONS_DISABLED")
        val candidates = pending.findAiCandidates(householdId).map { it.id }
        if (candidates.isEmpty()) throw rejected(householdId, by, "AI_SUGGESTION_NO_CANDIDATES")
        val run = try {
            runs.saveAndFlush(
                AiSuggestionRun(
                    householdId = householdId,
                    userId = by.id,
                    promptVersion = CategorySuggestionPrompt.VERSION,
                    model = provider.model,
                    batchesTotal = (candidates.size + AI_SUGGESTION_BATCH_SIZE - 1) / AI_SUGGESTION_BATCH_SIZE,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            // The partial unique index: another member (or another tab) pressed first.
            throw rejected(householdId, by, "AI_SUGGESTION_RUN_IN_PROGRESS")
        }
        log.info(
            "ai_suggestion_run_started run={} household={} user={} candidates={} batches={} batch_size={} model={} prompt={}",
            run.id, householdId, by.id, candidates.size, run.batchesTotal, AI_SUGGESTION_BATCH_SIZE, run.model,
            run.promptVersion,
        )
        executor.execute { runner.execute(run.id, householdId, candidates) }
        return runs.findById(run.id).orElseThrow().toDto()
    }

    private fun rejected(householdId: UUID, by: User, code: String): AppException {
        log.info("ai_suggestion_run_rejected household={} user={} reason={}", householdId, by.id, code)
        return AppException.conflict(code)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun interruptLeftoverRuns() {
        val interrupted = runs.interruptRunning(Instant.now())
        if (interrupted > 0) log.warn("ai_suggestion_runs_interrupted count={}", interrupted)
    }
}
