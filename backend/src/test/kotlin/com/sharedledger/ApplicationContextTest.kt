package com.sharedledger

import org.junit.jupiter.api.Test

class ApplicationContextTest : IntegrationTestBase() {
    @Test
    fun contextLoads() {
        // Boot the full context against a real Postgres so Flyway, JPA mappings, and security wiring are validated.
    }
}
