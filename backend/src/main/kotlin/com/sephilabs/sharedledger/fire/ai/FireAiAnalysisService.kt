package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.ai.AiCitationMapper
import com.sephilabs.sharedledger.bank.aisuggestion.AI_FEATURES_DISABLED
import com.sephilabs.sharedledger.bank.aisuggestion.AiAvailability
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettingsService
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.fire.FireService
import com.sephilabs.sharedledger.fire.history.FireHistoryService
import com.sephilabs.sharedledger.fire.levers.FireLeversService
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.getOrThrow
import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

const val FIRE_AI_FOLLOWUP_MAX_CHARS = 500

@Service
class FireAiAnalysisService(
    private val availability: AiAvailability,
    private val aiSettings: AiSuggestionSettingsService,
    private val fire: FireService,
    private val fireHistory: FireHistoryService,
    private val fireLevers: FireLeversService,
    private val households: HouseholdRepository,
    private val runs: FireAiAnalysisRunRepository,
    private val followups: FireAiAnalysisFollowupRepository,
    private val model: SuggestionModel,
    private val runner: FireAiAnalysisRunner,
    private val limiter: FireAiRateLimiter,
    @Qualifier("aiSuggestionExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<FireAiRunDto> {
        availability.requireConfigured()
        val stored = runs.findAllByHouseholdIdOrderByCreatedAtDesc(householdId)
        val byRun = followups.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).groupBy { it.runId }
        return stored.map { it.toDto(byRun[it.id].orEmpty()) }
    }

    /** Snapshots the household's live FIRE plan and launches the review off the request thread. */
    fun start(householdId: UUID, by: User): FireAiRunDto {
        val provider = availability.requireConfigured()
        if (!aiSettings.isEnabled(householdId)) throw AppException.conflict(AI_FEATURES_DISABLED)
        if (!limiter.tryAcquire(by.id)) throw AppException.tooManyRequests()

        val currency = households.getOrThrow(householdId).currency
        val projection = fire.project(householdId)
        val history = fireHistory.history(householdId)
        val levers = fireLevers.levers(householdId)
        val built = FireAiPayloadBuilder.build(currency, projection, history, levers)
        val locale = by.locale.take(2).lowercase().let { if (it == "es") "es" else "en" }

        val run = try {
            runs.saveAndFlush(
                FireAiAnalysisRun(
                    householdId = householdId,
                    userId = by.id,
                    model = provider.model,
                    promptVersion = FirePlanReviewPrompt.VERSION,
                    locale = locale,
                    referenceTier = built.referenceTier.name,
                    referenceScenarioIndex = built.referenceScenarioIndex,
                    referenceScenarioName = built.referenceScenarioName.take(120),
                    snapshotDate = projection.snapshotDate,
                    coveragePercent = built.coveragePercent,
                    estimatedYear = built.estimatedYear,
                    probability = built.probability,
                    payloadJson = built.json,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            throw AppException.conflict("FIRE_AI_RUN_IN_PROGRESS")
        }

        log.info(
            "fire_ai_analysis_started run={} household={} user={} tier={} scenario={} model={} prompt={} locale={}",
            run.id, householdId, by.id, run.referenceTier, run.referenceScenarioName, run.model, run.promptVersion, run.locale,
        )
        executor.execute { runner.execute(run.id) }
        val latest = runs.findById(run.id).orElse(null) ?: run
        val runFollowups = followups.findAllByRunIdOrderByCreatedAtAsc(run.id)
        return latest.toDto(runFollowups)
    }

    fun askFollowup(
        householdId: UUID,
        runId: UUID,
        by: User,
        request: FireAiFollowupRequest,
    ): FireAiFollowupDto {
        availability.requireConfigured()
        if (!aiSettings.isEnabled(householdId)) throw AppException.conflict(AI_FEATURES_DISABLED)
        val question = request.question?.trim() ?: ""
        if (question.isBlank() || question.length > FIRE_AI_FOLLOWUP_MAX_CHARS) {
            throw AppException.badRequest("FIRE_AI_QUESTION_INVALID", FIRE_AI_FOLLOWUP_MAX_CHARS)
        }
        if (!limiter.tryAcquire(by.id)) throw AppException.tooManyRequests()

        val run = runs.findByIdAndHouseholdId(runId, householdId)
            ?: throw AppException.notFound("FIRE_AI_RUN_NOT_FOUND")
        if (run.status != FireAiAnalysisRunStatus.completed) {
            throw AppException.conflict("FIRE_AI_RUN_NOT_COMPLETED")
        }

        val payloadNode = mapper.readTree(run.payloadJson)
        val summary = run.resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.path("summary")?.takeIf { it.isString }?.asString()
        val prior = followups.findAllByRunIdOrderByCreatedAtAsc(runId).map { it.question to it.answer }

        val prompt = ChatPrompt(
            system = FirePlanReviewPrompt.followupSystem(run.locale),
            user = FirePlanReviewPrompt.followupUser(run.payloadJson, summary, prior, question),
            sessionId = UUID.randomUUID(),
        )
        val rawAnswer = try {
            model.complete(prompt)
        } catch (ex: SuggestionModelException) {
            log.warn("fire_ai_followup run={} household={} outcome=failed error={}", runId, householdId, ex.message)
            throw AppException(code = "FIRE_AI_FOLLOWUP_FAILED", httpStatus = 502, cause = ex)
        }
        val parsed = JsonObjectReader.first(rawAnswer.content)
        val answerText = parsed?.path("answer")?.takeIf { it.isString }?.asString()?.trim()?.ifBlank { null }
            ?: throw AppException(code = "FIRE_AI_FOLLOWUP_FAILED", httpStatus = 502)

        val citationsNode = parsed.path("citations").takeIf { it.isArray } ?: mapper.createArrayNode()
        val grounding = FireAiGroundingVerifier.verifyCitations(payloadNode, citationsNode)
        val saved = followups.save(
            FireAiAnalysisFollowup(
                runId = run.id,
                householdId = householdId,
                userId = by.id,
                question = question,
                answer = answerText,
                citationsJson = mapper.writeValueAsString(citationsNode),
                citationsTotal = grounding.total,
                citationsUngrounded = grounding.ungrounded,
                inputTokens = rawAnswer.inputTokens,
                outputTokens = rawAnswer.outputTokens,
                reasoningTokens = rawAnswer.reasoningTokens,
                latencyMs = rawAnswer.latencyMs,
            ),
        )
        log.info(
            "fire_ai_followup run={} followup={} household={} citations={} ungrounded={} latency_ms={}",
            run.id, saved.id, householdId, grounding.total, grounding.ungrounded, rawAnswer.latencyMs,
        )
        return saved.toDto(payloadNode)
    }

    @Transactional
    fun delete(householdId: UUID, runId: UUID) {
        availability.requireConfigured()
        val run = runs.findByIdAndHouseholdId(runId, householdId)
            ?: throw AppException.notFound("FIRE_AI_RUN_NOT_FOUND")
        runs.delete(run)
        log.info("fire_ai_analysis_deleted run={} household={}", runId, householdId)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun interruptLeftoverRuns() {
        val count = runs.interruptRunning(Instant.now())
        if (count > 0) log.warn("fire_ai_analysis_runs_interrupted count={}", count)
    }

    // --- DTO mapping ---------------------------------------------------------

    private fun FireAiAnalysisRun.toDto(runFollowups: List<FireAiAnalysisFollowup>): FireAiRunDto {
        val payload = runCatching { mapper.readTree(payloadJson) }.getOrNull()
        val startYear = payload?.path("scope")?.path("startYear")?.takeIf { it.isNumber }?.asInt()
        val result = resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() }

        val strengths = result?.path("strengths")?.takeIf { it.isArray }?.toList()?.map { item ->
            FireAiStrengthDto(
                title = item.path("title").asString(""),
                detail = item.path("detail").asString(""),
                citations = citationsOf(item, payload),
            )
        } ?: emptyList()

        val improvements = result?.path("improvements")?.takeIf { it.isArray }?.toList()?.map { item ->
            FireAiImprovementDto(
                title = item.path("title").asString(""),
                detail = item.path("detail").asString(""),
                severity = item.path("severity").takeIf { it.isString }?.asString()?.lowercase(),
                citations = citationsOf(item, payload),
            )
        } ?: emptyList()

        val suggestedWhatIfs = result?.path("suggestedWhatIfs")?.takeIf { it.isArray }?.toList()?.map { item ->
            val previewNode = item.path("preview").takeIf { !it.isMissingNode && !it.isNull }
            FireAiSuggestedWhatIfDto(
                title = item.path("title").asString(""),
                rationale = item.path("rationale").asString(""),
                expectedImpact = item.path("expectedImpact").takeIf { it.isString }?.asString()?.ifBlank { null },
                preview = previewNode?.let { FireAiAnalysisRunner.sanitizePreview(it, startYear) },
                citations = citationsOf(item, payload),
            )
        } ?: emptyList()

        val observations = result?.path("observations")?.takeIf { it.isArray }?.toList()?.map { obs ->
            FireAiObservationDto(
                claim = obs.path("claim").asString(""),
                confidence = obs.path("confidence").takeIf { it.isString }?.asString()?.lowercase(),
                citations = citationsOf(obs, payload),
            )
        } ?: emptyList()

        val dataGaps = result?.path("dataGaps")?.takeIf { it.isArray }?.toList()?.map { item ->
            FireAiDataGapDto(
                topic = item.path("topic").asString(""),
                reason = item.path("reason").asString(""),
                citations = citationsOf(item, payload),
            )
        } ?: emptyList()

        return FireAiRunDto(
            id = id,
            status = status,
            createdAt = createdAt,
            finishedAt = finishedAt,
            scope = FireAiScopeDto(
                referenceTier = referenceTier,
                referenceScenarioIndex = referenceScenarioIndex,
                referenceScenarioName = referenceScenarioName,
                snapshotDate = snapshotDate,
                coveragePercent = coveragePercent,
                estimatedYear = estimatedYear,
                probability = probability,
            ),
            model = model,
            promptVersion = promptVersion,
            summary = result?.path("summary")?.takeIf { it.isString }?.asString()?.ifBlank { null },
            evolution = result?.path("evolution")?.takeIf { it.isString }?.asString()?.ifBlank { null },
            strengths = strengths,
            improvements = improvements,
            suggestedWhatIfs = suggestedWhatIfs,
            observations = observations,
            dataGaps = dataGaps,
            observationsCount = observationsCount,
            dataGapsCount = dataGapsCount,
            citationsTotal = citationsTotal,
            citationsUngrounded = citationsUngrounded,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            latencyMs = latencyMs,
            errorCode = errorCode,
            followups = runFollowups.map { it.toDto(payload) },
        )
    }

    private fun FireAiAnalysisFollowup.toDto(payload: JsonNode?): FireAiFollowupDto {
        val citationsNode = runCatching { mapper.readTree(citationsJson) }.getOrNull()
        val citations = citationsFromArray(citationsNode, payload)
        return FireAiFollowupDto(
            id = id,
            question = question,
            answer = answer,
            citations = citations,
            citationsTotal = if (payload != null) citations.size else citationsTotal,
            citationsUngrounded = if (payload != null) citations.count { !it.grounded } else citationsUngrounded,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs,
            createdAt = createdAt,
        )
    }

    private fun citationsOf(node: JsonNode, payload: JsonNode?): List<FireAiCitationDto> =
        AiCitationMapper.fromNode(node, payload, ::citationContext)

    private fun citationsFromArray(node: JsonNode?, payload: JsonNode?): List<FireAiCitationDto> =
        AiCitationMapper.fromArray(node, payload, ::citationContext)

    private fun citationContext(parent: JsonNode): String? {
        val name = parent.path("name").takeIf { it.isString }?.asString()
        if (!name.isNullOrBlank()) return name
        val key = parent.path("key").takeIf { it.isString }?.asString()
        if (!key.isNullOrBlank()) return key
        val label = parent.path("label").takeIf { it.isString }?.asString()
        val year = parent.path("year").takeIf { it.isNumber }?.asInt()
        if (!label.isNullOrBlank() && year != null) return "$label ($year)"
        val retYear = parent.path("retirementYear").takeIf { it.isNumber }?.asInt()
        if (retYear != null) return "Year $retYear"
        return null
    }
}
