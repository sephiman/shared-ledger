package com.sephilabs.sharedledger.bank.aisuggestion

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList

/** One movement as the provider received it, read back from the user message. */
data class SentMovement(val id: String, val fields: Map<String, Any?>) {
    val counterparty: String? get() = fields["counterparty"] as String?
}

/** Stands in for the provider: records every prompt and answers with [responder]. */
class ScriptedSuggestionModel : SuggestionModel {
    val prompts = CopyOnWriteArrayList<ChatPrompt>()
    @Volatile var responder: (ChatPrompt) -> ChatAnswer = { answer("[]") }

    override fun complete(prompt: ChatPrompt): ChatAnswer {
        prompts += prompt
        return responder(prompt)
    }

    fun reset() {
        prompts.clear()
        responder = { answer("[]") }
    }

    /** Answers each sent movement through [byCounterparty] (code to confidence; null = left out of the answer). */
    fun answerBy(byCounterparty: (String?) -> Pair<String, Double>?) {
        responder = { prompt ->
            val entries = sent(prompt).mapNotNull { m ->
                byCounterparty(m.counterparty)?.let { (code, confidence) ->
                    """{"id":"${m.id}","categoryCode":"$code","confidence":$confidence}"""
                }
            }
            answer(entries.joinToString(",", "[", "]"))
        }
    }

    companion object {
        private val json = JsonMapper.builder().build()

        fun answer(content: String?, inputTokens: Int = 100, outputTokens: Int = 20) =
            ChatAnswer(content, inputTokens, outputTokens, reasoningTokens = 5, latencyMs = 7, attempts = 1)

        fun sent(prompt: ChatPrompt): List<SentMovement> =
            prompt.user.lines().drop(1).filter { it.isNotBlank() }.map { line ->
                @Suppress("UNCHECKED_CAST")
                val fields = json.readValue(line, Map::class.java) as Map<String, Any?>
                SentMovement(fields["id"] as String, fields)
            }
    }
}

/** Lets a test pretend no provider is configured without booting a second Spring context. */
class TogglableAiAvailability(settings: AiProviderSettings) : AiAvailability(settings) {
    @Volatile var forced: Boolean? = null

    override val enabled: Boolean get() = forced ?: super.enabled
}

@TestConfiguration
class AiTestDoublesConfig {
    @Bean
    @Primary
    fun scriptedSuggestionModel() = ScriptedSuggestionModel()

    @Bean
    @Primary
    fun togglableAiAvailability(settings: AiProviderSettings) = TogglableAiAvailability(settings)
}
