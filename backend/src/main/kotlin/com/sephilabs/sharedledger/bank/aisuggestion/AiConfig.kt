package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class AiConfig {

    private val log = LoggerFactory.getLogger(AiConfig::class.java)

    @Bean
    fun aiProviderSettings(props: AppProperties): AiProviderSettings {
        val settings = AiProviderSettings.resolve(props.ai)
        when (settings) {
            is AiProviderSettings.Configured ->
                log.info("ai_configured host={} model={}", settings.host, settings.model)
            is AiProviderSettings.PartiallyConfigured ->
                log.warn(
                    "ai_partially_configured missing={} outcome=ai_suggestions_hidden",
                    settings.missing.joinToString(","),
                )
            AiProviderSettings.Absent -> log.info("ai_not_configured outcome=ai_suggestions_hidden")
        }
        return settings
    }

    @Bean
    fun suggestionModel(
        settings: AiProviderSettings,
        restClientBuilder: RestClient.Builder,
        buildProperties: ObjectProvider<BuildProperties>,
    ): SuggestionModel {
        if (settings !is AiProviderSettings.Configured) return UnconfiguredSuggestionModel
        val timeout = Duration.ofMillis(settings.timeoutMs)
        val jdk = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
            .apply { setReadTimeout(timeout) }
        // Buffered so the request carries Content-Length: streamed, the JDK client sends it chunked, which
        // not every OpenAI-compatible server accepts.
        val requestFactory = BufferingClientHttpRequestFactory(jdk)
        val version = buildProperties.ifAvailable?.version ?: "dev"
        return OpenAiCompatibleChatClient(settings, restClientBuilder.requestFactory(requestFactory), version)
    }

    /** One run at a time per household is enforced in the database; this only bounds the instance-wide
     *  number of concurrent provider conversations. Tests supply a synchronous executor of the same name. */
    @Bean("aiSuggestionExecutor")
    @Profile("!test")
    fun aiSuggestionExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 50
        setThreadNamePrefix("ai-suggestion-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }
}
