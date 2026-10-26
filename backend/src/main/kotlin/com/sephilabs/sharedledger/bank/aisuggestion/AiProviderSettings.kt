package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.config.DEFAULT_AI_BASE_URL
import com.sephilabs.sharedledger.config.DEFAULT_AI_MODEL
import java.net.URI

/** What the optional AI env group resolved to. Partial is deliberately *not* configured, as with SMTP. */
sealed interface AiProviderSettings {

    data class Configured(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val timeoutMs: Long,
    ) : AiProviderSettings {
        val host: String get() = URI(baseUrl).host

        override fun toString() = "Configured(baseUrl=$baseUrl, model=$model, timeoutMs=$timeoutMs)"
    }

    /** [missing] names the env variables still blank (or unusable), for the startup warning. */
    data class PartiallyConfigured(val missing: List<String>) : AiProviderSettings

    data object Absent : AiProviderSettings

    companion object {
        fun resolve(ai: AppProperties.Ai): AiProviderSettings {
            val baseUrl = ai.baseUrl.trim().trimEnd('/')
            val model = ai.model.trim()
            if (ai.apiKey.isBlank()) {
                // The defaults alone are not an attempt to configure the feature; a changed URL or model is.
                val touched = baseUrl != DEFAULT_AI_BASE_URL || model != DEFAULT_AI_MODEL
                return if (touched) PartiallyConfigured(listOf("AI_API_KEY")) else Absent
            }
            val missing = buildList {
                if (!isHttpUrl(baseUrl)) add("AI_BASE_URL")
                if (model.isEmpty()) add("AI_MODEL")
            }
            if (missing.isNotEmpty()) return PartiallyConfigured(missing)
            return Configured(baseUrl = baseUrl, apiKey = ai.apiKey.trim(), model = model, timeoutMs = ai.timeoutMs)
        }

        private fun isHttpUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
