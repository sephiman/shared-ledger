package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.AI_FEATURES_DISABLED
import com.sephilabs.sharedledger.bank.aisuggestion.AiAvailability
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettingsService
import com.sephilabs.sharedledger.bank.aisuggestion.PromptCatalogue
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** The question box: gated like every AI feature (no provider → 404, setting off → 409). The question text is
 *  never logged or stored — only its length. */
@Service
class QuestionService(
    private val availability: AiAvailability,
    private val settings: AiSuggestionSettingsService,
    private val catalogue: PromptCatalogue,
    private val interpreter: QuestionInterpreter,
    private val inFlight: QuestionsInFlight,
    private val categories: CategoryService,
    private val transactions: TransactionRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun ask(householdId: UUID, request: QuestionRequest, by: User): QuestionAnswerDto {
        val provider = requireOn(householdId, by)
        if (!inFlight.begin(by.id)) throw rejected(householdId, by, "QUESTION_IN_PROGRESS")
        try {
            val entries = catalogue.forHousehold(householdId)
            val outcome = try {
                interpreter.interpret(request.question, request.language, entries, LocalDate.now())
            } catch (ex: SuggestionModelException) {
                log.warn(
                    "ai_question household={} user={} asked_from={} target=none routed_because=none valid=false " +
                        "reason=provider_error unsupported=none question_chars={} attempts={} model={} prompt={} error={}",
                    householdId, by.id, request.askedFrom, request.question.length, ex.attempts, provider.model, QuestionPrompt.VERSION, ex.message,
                )
                throw AppException(code = "QUESTION_PROVIDER_ERROR", httpStatus = 502)
            }
            val route = (outcome.interpretation as? Interpretation.Understood)?.filters
                ?.let { QuestionRouting.route(it, request.askedFrom, entries) }
            logOutcome(householdId, by, request, outcome, route, provider.model)
            return outcome.interpretation.toDto(route)
        } finally {
            inFlight.end(by.id)
        }
    }

    /** The placeholder's example: the expense category with the most confirmed transactions in the last 12 months,
     *  else the first expense category in catalogue order. Read only here; nothing of it goes to the provider. */
    @Transactional(readOnly = true)
    fun exampleCategory(householdId: UUID, by: User): ExampleCategoryDto {
        requireOn(householdId, by)
        val expense = categories.listForHousehold(householdId).filter { it.kind == Direction.expense.name }
        val known = expense.map { it.code }.toSet()
        val today = LocalDate.now()
        val mostUsed = transactions.expenseCategoriesByUse(householdId, today.minusMonths(12), today).firstOrNull { it in known }
        return ExampleCategoryDto(mostUsed ?: expense.firstOrNull()?.code)
    }

    private fun requireOn(householdId: UUID, by: User) = availability.requireConfigured().also {
        if (!settings.isEnabled(householdId)) throw rejected(householdId, by, AI_FEATURES_DISABLED)
    }

    private fun logOutcome(
        householdId: UUID,
        by: User,
        request: QuestionRequest,
        outcome: QuestionOutcome,
        route: QuestionRoute?,
        model: String,
    ) {
        val interpretation = outcome.interpretation
        val understood = interpretation as? Interpretation.Understood
        val answer = outcome.answer
        log.info(
            "ai_question household={} user={} asked_from={} target={} routed_because={} valid={} reason={} unsupported={} " +
                "question_chars={} input_tokens={} output_tokens={} reasoning_tokens={} latency_ms={} attempts={} model={} prompt={}",
            householdId, by.id,
            request.askedFrom,
            route?.target ?: "none",
            route?.reason ?: "none",
            understood != null,
            (interpretation as? Interpretation.NotUnderstood)?.reason ?: "none",
            // Kinds only: a label can repeat words of the question.
            understood?.unsupported?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.kind.name } ?: "none",
            request.question.length,
            answer.inputTokens, answer.outputTokens, answer.reasoningTokens, answer.latencyMs, answer.attempts,
            model, QuestionPrompt.VERSION,
        )
    }

    private fun rejected(householdId: UUID, by: User, code: String): AppException {
        log.info("ai_question_rejected household={} user={} reason={}", householdId, by.id, code)
        return AppException.conflict(code)
    }
}
