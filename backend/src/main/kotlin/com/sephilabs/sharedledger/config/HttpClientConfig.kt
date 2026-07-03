package com.sephilabs.sharedledger.config

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

@Configuration
class HttpClientConfig {

    /**
     * Spring Boot 4 does not auto-configure a RestClient.Builder bean, so it is wired
     * explicitly. Prototype scope: builders are mutable, and each HTTP client sets its
     * own base URL and default headers (tests bind MockRestServiceServer per builder).
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
