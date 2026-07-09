package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class LendingScheduleMaterializerIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: LendingService,
    private val schedules: LendingScheduleRepository,
    private val payments: LendingPaymentRepository,
    private val materializer: LendingScheduleMaterializer,
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

        val firstRun = materializer.runForAll(LocalDate.of(2025, 4, 15))
        val firstCount = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id).size
        val secondRun = materializer.runForAll(LocalDate.of(2025, 4, 15))
        val secondCount = payments.findAllByLendingIdOrderByPaymentDateAsc(lending.id).size

        // Jan 1, Feb 1, Mar 1, Apr 1 = 4 payments
        assertThat(firstRun).isEqualTo(4)
        assertThat(firstCount).isEqualTo(4)
        assertThat(secondRun).isEqualTo(0)
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
        val created = materializer.runForAll(LocalDate.of(2025, 6, 1))
        assertThat(created).isEqualTo(0)
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

        val created = materializer.runForAll(today)

        assertThat(created).isEqualTo(0)
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

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "lm${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
