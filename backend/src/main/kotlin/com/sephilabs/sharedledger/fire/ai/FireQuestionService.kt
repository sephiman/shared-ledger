package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.analytics.question.QuestionsInFlight
import com.sephilabs.sharedledger.bank.aisuggestion.AI_FEATURES_DISABLED
import com.sephilabs.sharedledger.bank.aisuggestion.AiAvailability
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettingsService
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Translates a natural-language FIRE what-if question into structured adjustments without sending
 *  any household amounts or balances to the provider. Question text is never logged or stored. */
@Service
class FireQuestionService(
    private val availability: AiAvailability,
    private val settings: AiSuggestionSettingsService,
    private val model: SuggestionModel,
    private val inFlight: QuestionsInFlight,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun ask(householdId: UUID, request: FireQuestionRequest, by: User): FireQuestionAnswerDto {
        val provider = availability.requireConfigured()
        if (!settings.isEnabled(householdId)) {
            log.info("ai_fire_question household={} user={} rejected={}", householdId, by.id, AI_FEATURES_DISABLED)
            throw AppException.conflict(AI_FEATURES_DISABLED)
        }
        if (!inFlight.begin(by.id)) {
            log.info("ai_fire_question household={} user={} rejected=QUESTION_IN_PROGRESS", householdId, by.id)
            throw AppException.conflict("QUESTION_IN_PROGRESS")
        }
        try {
            val today = LocalDate.now(clock)
            val prompt = ChatPrompt(
                system = FireQuestionPrompt.system(today, request.language),
                user = FireQuestionPrompt.user(request.question),
                sessionId = UUID.randomUUID(),
            )
            val answer = try {
                model.complete(prompt)
            } catch (ex: SuggestionModelException) {
                log.warn(
                    "ai_fire_question household={} user={} valid=false reason=provider_error " +
                        "question_chars={} attempts={} model={} prompt={} error={}",
                    householdId, by.id, request.question.length, ex.attempts, provider.model, FireQuestionPrompt.VERSION, ex.message,
                )
                throw AppException(code = "QUESTION_PROVIDER_ERROR", httpStatus = 502)
            }
            val interpretation = when (val parsed = FireQuestionParser.parse(answer.content)) {
                is ParsedFireQuestionAnswer.Unreadable -> FireQuestionInterpretation.NotUnderstood(parsed.reason)
                is ParsedFireQuestionAnswer.Read -> FireQuestionValidator.validate(parsed, today)
            }
            val understood = interpretation as? FireQuestionInterpretation.Understood
            log.info(
                "ai_fire_question household={} user={} valid={} reason={} has_what_if={} events={} unsupported={} " +
                    "question_chars={} input_tokens={} output_tokens={} reasoning_tokens={} latency_ms={} attempts={} model={} prompt={}",
                householdId, by.id,
                understood != null,
                (interpretation as? FireQuestionInterpretation.NotUnderstood)?.reason ?: "none",
                understood?.whatIf != null,
                understood?.whatIf?.cashFlowEvents?.size ?: 0,
                understood?.unsupported?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.kind.name } ?: "none",
                request.question.length,
                answer.inputTokens, answer.outputTokens, answer.reasoningTokens,
                answer.latencyMs, answer.attempts,
                provider.model, FireQuestionPrompt.VERSION,
            )
            return interpretation.toDto()
        } finally {
            inFlight.end(by.id)
        }
    }
}
