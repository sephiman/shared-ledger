package com.sephilabs.sharedledger.fire

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** "Today" for the FIRE photo; a fixed clock lets a test take the photo as it looked on a past date. */
@Configuration
class FireClockConfig {

    @Bean
    fun fireClock(): Clock = Clock.systemDefaultZone()
}
