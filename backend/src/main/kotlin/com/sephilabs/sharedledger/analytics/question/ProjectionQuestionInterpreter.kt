package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.bank.aisuggestion.ChatAnswer
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

data class ProjectionQuestionOutcome(val interpretation: ProjectionInterpretation, val answer: ChatAnswer)

@Component
class ProjectionQuestionInterpreter(private val model: SuggestionModel) {

    fun interpret(
        question: String,
        language: QuestionLanguage,
        catalogue: List<CatalogueEntry>,
        today: LocalDate,
    ): ProjectionQuestionOutcome {
        val prompt = ChatPrompt(
            system = ProjectionQuestionPrompt.system(catalogue, today, language),
            user = ProjectionQuestionPrompt.user(question),
            sessionId = UUID.randomUUID(),
        )
        val answer = model.complete(prompt)
        val interpretation = when (val parsed = ProjectionQuestionParser.parse(answer.content)) {
            is ParsedProjectionAnswer.Unreadable -> ProjectionInterpretation.NotUnderstood(parsed.reason)
            is ParsedProjectionAnswer.Read -> ProjectionQuestionValidator.validate(parsed, catalogue)
        }
        return ProjectionQuestionOutcome(interpretation, answer)
    }
}
