package com.sephilabs.sharedledger.bank.aisuggestion

import java.util.UUID

/** The edge to the language model, so the run logic is testable without a provider. */
interface SuggestionModel {
    /** Throws [SuggestionModelException] once the call (and its one retry) has failed. */
    fun complete(prompt: ChatPrompt): ChatAnswer
}

data class ChatPrompt(val system: String, val user: String, val sessionId: UUID)

data class ChatAnswer(
    val content: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val latencyMs: Long,
    val attempts: Int,
)

class SuggestionModelException(message: String, val attempts: Int, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Stands in when no provider is configured; every caller is behind [AiAvailability], so reaching it is a bug. */
object UnconfiguredSuggestionModel : SuggestionModel {
    override fun complete(prompt: ChatPrompt): ChatAnswer =
        throw IllegalStateException("AI provider is not configured")
}
