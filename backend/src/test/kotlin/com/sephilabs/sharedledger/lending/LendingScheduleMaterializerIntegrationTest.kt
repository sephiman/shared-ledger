package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

// runForAll sweeps every active schedule in the database, so it must not run while another class's
// schedules are mid-test — see LendingLifecycleIntegrationTest.
@ResourceLock("lending-schedules")
class LendingScheduleMaterializerIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: LendingService,
    private val schedules: LendingScheduleRepository,
    private val payments: LendingPaymentRepository,
    private val materializer: LendingScheduleMaterializer,
    private val txManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) : IntegrationTestBase() {

    @Test
    fun `materializer fires monthly schedule and is idempotent`() {
        val (user, household) = seed()
        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Eve ${System.nanoTime()}",
                principalAmount = BigDecimal("1200.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.none,
            ),
            user,
        )
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(
                frequency = LendingFrequency.monthly,
                dayOfMonth = 1,
                expectedAmount = BigDecimal("100.00"),
            ),
            user,
        )
        // Anchor watermark just before the lending start so the test exercises
        // catch-up over the full Jan-Apr range deterministically.
        schedules.findByLendingId(lending.id)!!.apply {
            lastMaterializedThrough = LocalDate.of(2024, 12, 31)
            schedules.save(this)
        }

        // runForAll's return sums every household's schedules, so assert on this lending's rows only.
        materializer.runForAll(LocalDate.of(2025, 4, 15))
        val firstCount = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id).size
        materializer.runForAll(LocalDate.of(2025, 4, 15))
        val secondCount = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id).size

        // Jan 1, Feb 1, Mar 1, Apr 1 = 4 payments
        assertThat(firstCount).isEqualTo(4)
        assertThat(secondCount).isEqualTo(firstCount)

        val schedule = schedules.findByLendingId(lending.id)!!
        assertThat(schedule.lastMaterializedThrough).isEqualTo(LocalDate.of(2025, 4, 15))
    }

    @Test
    fun `paused schedule does not materialize`() {
        val (user, household) = seed()
        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Frank ${System.nanoTime()}",
                principalAmount = BigDecimal("500.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.none,
            ),
            user,
        )
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(
                frequency = LendingFrequency.monthly,
                dayOfMonth = 5,
                expectedAmount = BigDecimal("50.00"),
                active = false,
            ),
            user,
        )
        materializer.runForAll(LocalDate.of(2025, 6, 1))
        assertThat(payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id)).isEmpty()
    }

    @Test
    fun `new schedule with backdated lending start does not back-fill past periods`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Hank ${System.nanoTime()}",
                principalAmount = BigDecimal("6000.00"),
                startDate = today.minusYears(1),
                interestType = InterestType.none,
            ),
            user,
        )
        // Pick a dayOfMonth that cannot equal today, so the first nightly run
        // finds zero in-range occurrences.
        val differentDay = if (today.dayOfMonth == 1) 2 else 1
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(
                frequency = LendingFrequency.monthly,
                dayOfMonth = differentDay.toShort(),
                expectedAmount = BigDecimal("500.00"),
            ),
            user,
        )

        materializer.runForAll(today)

        assertThat(payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id)).isEmpty()
        val schedule = schedules.findByLendingId(lending.id)!!
        assertThat(schedule.lastMaterializedThrough).isEqualTo(today)
    }

    @Test
    fun `fireNow force-fires today even when today is not the cadence day`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Ivy ${System.nanoTime()}",
                principalAmount = BigDecimal("1000.00"),
                startDate = today.minusMonths(1),
                interestType = InterestType.none,
            ),
            user,
        )
        val differentDay = if (today.dayOfMonth == 1) 2 else 1
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(
                frequency = LendingFrequency.monthly,
                dayOfMonth = differentDay.toShort(),
                expectedAmount = BigDecimal("200.00"),
            ),
            user,
        )

        val created = materializer.fireNow(household.id, lending.id, user, today)

        assertThat(created).isEqualTo(1)
        val rows = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().paymentDate).isEqualTo(today)
        assertThat(schedules.findByLendingId(lending.id)!!.lastMaterializedThrough).isEqualTo(today)
    }

    @Test
    fun `settling lending pauses schedule and stops new payments`() {
        val (user, household) = seed()
        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Gina ${System.nanoTime()}",
                principalAmount = BigDecimal("400.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.none,
            ),
            user,
        )
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(LendingFrequency.monthly, dayOfMonth = 1, expectedAmount = BigDecimal("100.00")),
            user,
        )
        service.settle(household.id, lending.id, LendingStatusTransitionRequest(), user)
        val schedule = schedules.findByLendingId(lending.id)!!
        assertThat(schedule.active).isFalse
    }

    @Test
    fun `a sweep racing a settle never re-activates the settled schedule`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val lending = service.create(
            household.id,
            LendingRequest("Ivy ${System.nanoTime()}", BigDecimal("400.00"), today.minusMonths(2), interestType = InterestType.none),
            user,
        )
        val notToday = if (today.dayOfMonth == 1) 2 else 1
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(LendingFrequency.monthly, dayOfMonth = notToday.toShort(), expectedAmount = BigDecimal("100.00")),
            user,
        )
        val sweeper = Executors.newSingleThreadExecutor()
        try {
            // The settle side holds the row lock uncommitted while the sweep reads the old `active = true`
            // and queues its write behind that lock — the exact interleaving of the nightly race.
            TransactionTemplate(txManager).execute {
                val schedule = schedules.findByLendingId(lending.id)!!
                schedule.active = false
                schedules.saveAndFlush(schedule)
                val sweep = sweeper.submit<Int> { materializer.runForAll(today) }
                awaitBlockedScheduleUpdate()
                check(!sweep.isDone) { "sweep finished without touching the locked schedule" }
            }
            sweeper.shutdown()
            assertThat(sweeper.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            sweeper.shutdownNow()
        }

        val schedule = schedules.findByLendingId(lending.id)!!
        assertThat(schedule.active).isFalse()
        assertThat(schedule.lastMaterializedThrough).isEqualTo(today)
    }

    /** Waits (bounded) until another session queues behind this transaction's row lock. */
    private fun awaitBlockedScheduleUpdate() {
        val deadline = Instant.now().plus(Duration.ofSeconds(15))
        while (Instant.now().isBefore(deadline)) {
            // pg_stat_activity is snapshotted once per transaction; this poll runs inside the locking one.
            jdbc.queryForObject("SELECT pg_stat_clear_snapshot()::text", String::class.java)
            val blocked = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE pg_backend_pid() = ANY(pg_blocking_pids(pid))",
                Int::class.java,
            )
            if (blocked != null && blocked > 0) return
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos())
        }
        error("the sweep never reached the locked schedule")
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "lm${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
