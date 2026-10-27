package com.sephilabs.sharedledger.bank.rulegeneralisation

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@Configuration
class RuleGeneralisationConfig {

    @Bean
    fun ruleGeneralisationProposals() = RuleGeneralisationProposals(Clock.systemUTC(), Duration.ofMinutes(30))
}
