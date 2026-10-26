package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AiProviderSettingsTest {

    @Test
    fun `no key with the default URL and model is absent, not partial`() {
        assertThat(AiProviderSettings.resolve(AppProperties.Ai())).isEqualTo(AiProviderSettings.Absent)
    }

    @Test
    fun `a key alone is configured with the OpenCode defaults`() {
        val settings = AiProviderSettings.resolve(AppProperties.Ai(apiKey = "k"))

        assertThat(settings).isInstanceOf(AiProviderSettings.Configured::class.java)
        settings as AiProviderSettings.Configured
        assertThat(settings.baseUrl).isEqualTo("https://opencode.ai/zen/go/v1")
        assertThat(settings.model).isEqualTo("deepseek-v4.1-flash")
        assertThat(settings.host).isEqualTo("opencode.ai")
    }

    @Test
    fun `a trailing slash on the base URL is dropped`() {
        val settings = AiProviderSettings.resolve(AppProperties.Ai(apiKey = "k", baseUrl = "https://api.example.com/v1/"))
        assertThat((settings as AiProviderSettings.Configured).baseUrl).isEqualTo("https://api.example.com/v1")
    }

    @Test
    fun `a changed URL or model without a key is partial, naming the key`() {
        assertThat(AiProviderSettings.resolve(AppProperties.Ai(model = "other-model")))
            .isEqualTo(AiProviderSettings.PartiallyConfigured(listOf("AI_API_KEY")))
        assertThat(AiProviderSettings.resolve(AppProperties.Ai(baseUrl = "https://api.example.com/v1")))
            .isEqualTo(AiProviderSettings.PartiallyConfigured(listOf("AI_API_KEY")))
    }

    @Test
    fun `a key with a blank or unusable URL or a blank model is partial, naming what is missing`() {
        assertThat(AiProviderSettings.resolve(AppProperties.Ai(apiKey = "k", baseUrl = "", model = "")))
            .isEqualTo(AiProviderSettings.PartiallyConfigured(listOf("AI_BASE_URL", "AI_MODEL")))
        assertThat(AiProviderSettings.resolve(AppProperties.Ai(apiKey = "k", baseUrl = "opencode.ai/zen")))
            .isEqualTo(AiProviderSettings.PartiallyConfigured(listOf("AI_BASE_URL")))
    }

    @Test
    fun `the key never appears in the settings' string form`() {
        assertThat(AiProviderSettings.resolve(AppProperties.Ai(apiKey = "super-secret")).toString())
            .doesNotContain("super-secret")
    }
}
