package com.sephilabs.sharedledger.bank.aisuggestion

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode

/** Plain `POST {base}/chat/completions`: no tools, no structured output, no streaming, so any
 *  OpenAI-compatible provider works by changing env only. One retry on 5xx or timeout/network. */
class OpenAiCompatibleChatClient(
    private val settings: AiProviderSettings.Configured,
    restClientBuilder: RestClient.Builder,
    appVersion: String,
) : SuggestionModel {

    private val log = LoggerFactory.getLogger(javaClass)

    private val rest: RestClient = restClientBuilder
        .baseUrl(settings.baseUrl)
        .defaultHeader("Authorization", "Bearer ${settings.apiKey}")
        // Our own name, never an imitation of a coding agent's.
        .defaultHeader("User-Agent", "shared-ledger/$appVersion")
        .build()

    override fun complete(prompt: ChatPrompt): ChatAnswer {
        val body = mapOf(
            "model" to settings.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to prompt.system),
                mapOf("role" to "user", "content" to prompt.user),
            ),
            "temperature" to 0,
            "stream" to false,
        )
        var attempt = 0
        while (true) {
            attempt++
            val started = System.nanoTime()
            try {
                val response = rest.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    // OpenCode routes on it and answers 400 MissingSessionID without; other providers ignore it.
                    .header(SESSION_HEADER, prompt.sessionId.toString())
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
                    ?: throw SuggestionModelException("empty response body", attempt)
                return toAnswer(response, (System.nanoTime() - started) / 1_000_000, attempt)
            } catch (ex: RestClientResponseException) {
                val failure = SuggestionModelException(
                    "HTTP ${ex.statusCode.value()}: ${ex.responseBodyAsString.take(ERROR_BODY_CHARS)}",
                    attempt,
                    ex,
                )
                if (!ex.statusCode.is5xxServerError || attempt >= MAX_ATTEMPTS) throw failure
                logRetry(prompt, attempt, "http_${ex.statusCode.value()}", started)
            } catch (ex: ResourceAccessException) {
                if (attempt >= MAX_ATTEMPTS) throw SuggestionModelException("timeout or network: ${ex.message}", attempt, ex)
                logRetry(prompt, attempt, "timeout_or_network", started)
            } catch (ex: RestClientException) {
                // A 2xx we cannot read (an HTML error page, a truncated body): retrying would get the same.
                throw SuggestionModelException("unreadable response: ${ex.message}", attempt, ex)
            }
        }
    }

    private fun logRetry(prompt: ChatPrompt, attempt: Int, cause: String, started: Long) {
        log.warn(
            "ai_provider_retry session={} attempt={} of={} cause={} after_ms={}",
            prompt.sessionId, attempt, MAX_ATTEMPTS, cause, (System.nanoTime() - started) / 1_000_000,
        )
    }

    private fun toAnswer(response: JsonNode, latencyMs: Long, attempts: Int): ChatAnswer {
        val usage = response.path("usage")
        val content = response.path("choices").path(0).path("message").path("content")
        return ChatAnswer(
            content = if (content.isString) content.asString() else null,
            inputTokens = usage.path("prompt_tokens").intOrNull(),
            outputTokens = usage.path("completion_tokens").intOrNull(),
            reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").intOrNull(),
            latencyMs = latencyMs,
            attempts = attempts,
        )
    }

    private fun JsonNode.intOrNull(): Int? = if (isNumber) asInt() else null

    private companion object {
        const val SESSION_HEADER = "x-opencode-session"
        const val MAX_ATTEMPTS = 2
        const val ERROR_BODY_CHARS = 300
    }
}
