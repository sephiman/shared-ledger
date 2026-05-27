package com.sephilabs.sharedledger.loan

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

class LoanScheduleMaterializerIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: LoanService,
    private val schedules: LoanScheduleRepository,
    private val payments: LoanPaymentRepository,
    private val materializer: LoanScheduleMaterializer,
) : IntegrationTestBase() {

    @Test
    fun `materializer fires monthly schedule and is idempotent`() {
        val (user, household) = seed()
        val loan = service.create(
            household.id,
            LoanRequest(
                borrowerName = "Eve ${System.nanoTime()}",
                principalAmount = BigDecimal("1200.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.none,
            ),
            user,
        )
        service.upsertSchedule(
            household.id, loan.id,
            LoanScheduleRequest(
                frequency = LoanFrequency.monthly,
                dayOfMonth = 1,
                expectedAmount = BigDecimal("100.00"),
            ),
            user,
        )

        val firstRun = materializer.runForAll(LocalDate.of(2025, 4, 15))
        val firstCount = payments.findAllByLoanIdOrderByPaymentDateAsc(loan.id).size
        val secondRun = materializer.runForAll(LocalDate.of(2025, 4, 15))
        val secondCount = payments.findAllByLoanIdOrderByPaymentDateAsc(loan.id).size

        // Jan 1, Feb 1, Mar 1, Apr 1 = 4 payments
        assertThat(firstRun).isEqualTo(4)
        assertThat(firstCount).isEqualTo(4)
        assertThat(secondRun).isEqualTo(0)
        assertThat(secondCount).isEqualTo(firstCount)

        val schedule = schedules.findByLoanId(loan.id)!!
        assertThat(schedule.lastMaterializedThrough).isEqualTo(LocalDate.of(2025, 4, 15))
    }

    @Test
    fun `paused schedule does not materialize`() {
        val (user, household) = seed()
        val loan = service.create(
            household.id,
            LoanRequest(
                borrowerName = "Frank ${System.nanoTime()}",
                principalAmount = BigDecimal("500.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.none,
            ),
            user,
        )
        service.upsertSchedule(
            household.id, loan.id,
            LoanScheduleRequest(
                frequency = LoanFrequency.monthly,
                dayOfMonth = 5,
                expectedAmount = BigDecimal("50.00"),
                active = false,
            ),
            user,
        )
        val created = materializer.runForAll(LocalDate.of(2025, 6, 1))
        assertThat(created).isEqualTo(0)
        assertThat(payments.findAllByLoanIdOrderByPaymentDateAsc(loan.id)).isEmpty()
    }

    @Test
    fun `settling loan pauses schedule and stops new payments`() {
        val (user, household) = seed()
        val loan = service.create(
            household.id,
            LoanRequest(
                borrowerName = "Gina ${System.nanoTime()}",
                principalAmount = BigDecimal("400.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.none,
            ),
            user,
        )
        service.upsertSchedule(
            household.id, loan.id,
            LoanScheduleRequest(LoanFrequency.monthly, dayOfMonth = 1, expectedAmount = BigDecimal("100.00")),
            user,
        )
        service.settle(household.id, loan.id, LoanStatusTransitionRequest(), user)
        val schedule = schedules.findByLoanId(loan.id)!!
        assertThat(schedule.active).isFalse
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "lm${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
