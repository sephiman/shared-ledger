package com.sephilabs.sharedledger

import com.sephilabs.sharedledger.bank.FakeBankConnectorConfig
import com.sephilabs.sharedledger.notification.RecordingTelegramConfig
import com.sephilabs.sharedledger.portfolio.StubPriceProviderConfig
import com.sephilabs.sharedledger.portfolio.benchmark.StubBenchmarkSourceConfig
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Replaces the production `backfillExecutor` (excluded from the test profile). Runs the after-commit
 *  backfill on a dedicated worker so it gets a clean transaction context like production, but blocks, so
 *  tests can assert on backfill results right after create/link/addLot. */
@TestConfiguration
class TestBackfillExecutorConfig {
    @Bean("backfillExecutor")
    fun backfillExecutor(): Executor {
        val worker = Executors.newSingleThreadExecutor()
        return Executor { task -> worker.submit(task).get() }
    }

    /** Same rationale as [backfillExecutor]: run the after-commit bank sync on a worker but block. */
    @Bean("bankSyncExecutor")
    fun bankSyncExecutor(): Executor {
        val worker = Executors.newSingleThreadExecutor()
        return Executor { task -> worker.submit(task).get() }
    }

    /** Same rationale again: notification dispatch has finished by the time the test asserts on it. */
    @Bean("telegramExecutor")
    fun telegramExecutor(): Executor {
        val worker = Executors.newSingleThreadExecutor()
        return Executor { task -> worker.submit(task).get() }
    }

    /** Replaces the production `passwordEncoder` (excluded from the test profile). Argon2 at production
     *  strength is ~50ms a hash by design, and the suite hashes on every register, login and seeded user.
     *  Same algorithm and the same encode/matches code path — only the work factor drops, so a hash
     *  written under this encoder still verifies under it. */
    @Bean("passwordEncoder")
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder(16, 32, 1, 1024, 1)
}

/**
 * Rebuilds the schema once per run, in place of Flyway's plain migrate. A container reused across runs
 * (see [IntegrationTestBase.postgres]) still holds the previous run's rows, and the suite writes to
 * tables keyed globally rather than per household — prices, fx rates, benchmark series — where a
 * leftover row is a duplicate-key failure. Clean rather than truncate because the migrations seed
 * reference data the suite reads back. Costs ~0.3s against the several seconds a container start takes.
 */
@TestConfiguration
class TestSchemaResetConfig {
    @Bean
    fun flywayMigrationStrategy() = FlywayMigrationStrategy { flyway ->
        flyway.clean()
        flyway.migrate()
    }
}

/**
 * Every stub/fake is imported here rather than per test class on purpose. Spring keys its context
 * cache on the merged `@Import` set, so each distinct combination used to boot a whole extra
 * application context — six of them across the suite. One shared list means one context.
 *
 * Stubs a given test does not care about are harmless: they only stand in for outbound I/O, so
 * importing them everywhere also stops a new test from silently reaching a real provider.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(
    TestSchemaResetConfig::class,
    TestBackfillExecutorConfig::class,
    StubPriceProviderConfig::class,
    FakeBankConnectorConfig::class,
    RecordingTelegramConfig::class,
    StubBenchmarkSourceConfig::class,
)
abstract class IntegrationTestBase {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("sharedledger_test")
            .withUsername("test")
            .withPassword("test")
            // No-op unless the developer opts in with `testcontainers.reuse.enable=true` in
            // ~/.testcontainers.properties, so CI keeps getting a throwaway container. When it is on,
            // [TestSchemaResetConfig] supplies the clean slate a fresh container gave for free.
            .withReuse(true)
            .also { it.start() }
    }
}
