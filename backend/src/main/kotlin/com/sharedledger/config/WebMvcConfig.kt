package com.sharedledger.config

import com.sharedledger.household.HouseholdAccessInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(private val householdAccessInterceptor: HouseholdAccessInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(householdAccessInterceptor)
            .addPathPatterns("/api/households/**")
    }
}
