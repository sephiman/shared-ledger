package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.bank.aisuggestion.ChatAnswer
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

const val QUESTION_MAX_CHARS = 300

data class QuestionOutcome(val interpretation: Interpretation, val answer: ChatAnswer)

/** One question, one provider call (plus the client's single retry), then parse and validate. */
@Component
class QuestionInterpreter(private val model: SuggestionModel) {

    /** Throws [com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException] when the provider never answered. */
    fun interpret(question: String, language: QuestionLanguage, catalogue: List<CatalogueEntry>, today: LocalDate): QuestionOutcome {
        val prompt = ChatPrompt(
            system = QuestionPrompt.system(catalogue, today, language),
            user = QuestionPrompt.user(question),
            sessionId = UUID.randomUUID(),
        )
        val answer = model.complete(prompt)
        val interpretation = when (val parsed = QuestionAnswerParser.parse(answer.content)) {
            is ParsedAnswer.Unreadable -> Interpretation.NotUnderstood(parsed.reason)
            is ParsedAnswer.Read -> QuestionAnswerValidator.validate(parsed, catalogue, today)
        }
        return QuestionOutcome(interpretation, answer)
    }
}
