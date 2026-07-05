package com.sephilabs.sharedledger

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Replaces the production thread-pool `backfillExecutor` (excluded from the test profile). Runs
 * the after-commit backfill on a dedicated worker thread — so it gets a clean transaction context
 * like production, rather than nesting inside the just-committed request transaction still bound to
 * the callback thread — but blocks until it finishes, so tests can assert on backfill results right
 * after create/link/addLot.
 */
@TestConfiguration
class TestBackfillExecutorConfig {
    @Bean("backfillExecutor")
    fun backfillExecutor(): Executor {
        val worker = Executors.newSingleThreadExecutor()
        return Executor { task -> worker.submit(task).get() }
    }
}

@SpringBootTest
@ActiveProfiles("test")
@Import(TestBackfillExecutorConfig::class)
abstract class IntegrationTestBase {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("sharedledger_test")
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }
    }
}
