package com.sephilabs.sharedledger.observability

import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SessionGaugeConfig(
    private val jdbc: JdbcTemplate,
    private val metrics: AppMetrics,
) {
    @PostConstruct
    fun register() {
        metrics.registerActiveSessionsGauge {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE EXPIRY_TIME > ?",
                Long::class.java,
                Instant.now().toEpochMilli()
            ) ?: 0L
        }
    }
}
