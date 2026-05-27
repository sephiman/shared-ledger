package com.sharedledger.notification

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.sharedledger.config.AppProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Thin wrapper over the Telegram Bot API `sendMessage` endpoint.
 *
 * Errors (bad token, unknown chat, network) are returned as [SendResult] with `ok=false` and the
 * Telegram-provided description rather than thrown, so the synchronous Test endpoint can surface
 * the raw verdict inline and the async listener can log it without retrying.
 */
@Component
class TelegramClient(props: AppProperties) {
    private val rest: RestClient = RestClient.builder().baseUrl(props.telegram.baseUrl).build()

    data class SendResult(val ok: Boolean, val description: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TelegramResponse(val ok: Boolean = false, val description: String? = null)

    fun sendMessage(token: String, chatId: String, markdownText: String): SendResult {
        return try {
            val body = mapOf(
                "chat_id" to chatId,
                "text" to markdownText,
                "parse_mode" to "Markdown",
                "disable_web_page_preview" to true,
            )
            val response = rest.post()
                .uri("/bot{token}/sendMessage", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus({ it.isError }) { _, _ -> /* swallow: parse the error body below */ }
                .body(TelegramResponse::class.java)
            SendResult(response?.ok ?: false, response?.description)
        } catch (ex: Exception) {
            SendResult(false, ex.message)
        }
    }
}
