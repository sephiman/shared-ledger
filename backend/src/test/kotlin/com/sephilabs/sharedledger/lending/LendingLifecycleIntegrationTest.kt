package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class LendingLifecycleIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: LendingService,
) : IntegrationTestBase() {

    @Test
    fun `create, register payments, recompute on delete, settle`() {
        val (user, household) = seed()

        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Alice",
                principalAmount = BigDecimal("1000.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.simple,
                annualInterestRate = BigDecimal("10"),
            ),
            user,
        )

        service.registerPayment(
            household.id, lending.id,
            LendingPaymentRequest(LocalDate.of(2025, 6, 1), BigDecimal("200.00")),
            user,
        )
        val secondPayment = service.registerPayment(
            household.id, lending.id,
            LendingPaymentRequest(LocalDate.of(2025, 9, 1), BigDecimal("300.00")),
            user,
        )

        val afterTwo = service.get(household.id, lending.id)
        val outstandingAfterTwo = afterTwo.summary.totalOutstanding

        service.deletePayment(household.id, lending.id, secondPayment.id, user)
        val afterDelete = service.get(household.id, lending.id)
        assertThat(afterDelete.payments).hasSize(1)
        assertThat(afterDelete.summary.totalOutstanding).isGreaterThan(outstandingAfterTwo)

        service.settle(household.id, lending.id, LendingStatusTransitionRequest(LocalDate.of(2025, 12, 31)), user)
        val settled = service.get(household.id, lending.id)
        assertThat(settled.summary.status).isEqualTo(LendingStatus.settled)
        assertThat(settled.summary.closedDate).isEqualTo(LocalDate.of(2025, 12, 31))

        assertThatThrownBy {
            service.registerPayment(
                household.id, lending.id,
                LendingPaymentRequest(LocalDate.of(2026, 1, 1), BigDecimal("100.00")),
                user,
            )
        }.hasMessageContaining("LENDING_NOT_ACTIVE")
    }

    @Test
    fun `final payment auto-settles the lending`() {
        val (user, household) = seed()
        val lending = service.create(
            household.id,
            LendingRequest("Eve", BigDecimal("500.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        service.upsertSchedule(
            household.id, lending.id,
            LendingScheduleRequest(LendingFrequency.monthly, dayOfMonth = 1, expectedAmount = BigDecimal("100.00"), active = true),
            user,
        )

        service.registerPayment(
            household.id, lending.id,
            LendingPaymentRequest(LocalDate.of(2025, 2, 1), BigDecimal("200.00")),
            user,
        )
        assertThat(service.get(household.id, lending.id).summary.status).isEqualTo(LendingStatus.active)

        service.registerPayment(
            household.id, lending.id,
            LendingPaymentRequest(LocalDate.of(2025, 3, 1), BigDecimal("300.00")),
            user,
        )
        val settled = service.get(household.id, lending.id)
        assertThat(settled.summary.status).isEqualTo(LendingStatus.settled)
        assertThat(settled.summary.closedDate).isEqualTo(LocalDate.of(2025, 3, 1))
        assertThat(settled.summary.totalOutstanding).isEqualByComparingTo("0.00")
        assertThat(settled.summary.scheduleActive).isFalse()
    }

    @Test
    fun `payment covering total outstanding settles interest-bearing lending`() {
        val (user, household) = seed()
        val lending = service.create(
            household.id,
            LendingRequest(
                borrowerName = "Frank",
                principalAmount = BigDecimal("1000.00"),
                startDate = LocalDate.of(2025, 1, 1),
                interestType = InterestType.simple,
                annualInterestRate = BigDecimal("10"),
            ),
            user,
        )
        val outstanding = service.get(household.id, lending.id).summary.totalOutstanding

        service.registerPayment(household.id, lending.id, LendingPaymentRequest(LocalDate.now(), outstanding), user)

        val settled = service.get(household.id, lending.id)
        assertThat(settled.summary.status).isEqualTo(LendingStatus.settled)
        assertThat(settled.summary.closedDate).isEqualTo(LocalDate.now())
        assertThat(settled.summary.totalOutstanding).isEqualByComparingTo("0.00")
    }

    @Test
    fun `payment update that clears the balance auto-settles`() {
        val (user, household) = seed()
        val lending = service.create(
            household.id,
            LendingRequest("Grace", BigDecimal("500.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        val payment = service.registerPayment(
            household.id, lending.id,
            LendingPaymentRequest(LocalDate.of(2025, 2, 1), BigDecimal("400.00")),
            user,
        )
        assertThat(service.get(household.id, lending.id).summary.status).isEqualTo(LendingStatus.active)

        service.updatePayment(
            household.id, lending.id, payment.id,
            LendingPaymentRequest(LocalDate.of(2025, 2, 1), BigDecimal("500.00")),
            user,
        )
        val settled = service.get(household.id, lending.id)
        assertThat(settled.summary.status).isEqualTo(LendingStatus.settled)
        assertThat(settled.summary.closedDate).isEqualTo(LocalDate.of(2025, 2, 1))
    }

    @Test
    fun `list returns active outstanding total and top-N`() {
        val (user, household) = seed()
        service.create(
            household.id,
            LendingRequest("Bob", BigDecimal("500.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        service.create(
            household.id,
            LendingRequest("Carol", BigDecimal("2000.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        val list = service.list(household.id, status = "active", top = 2)
        assertThat(list.activeCount).isEqualTo(2)
        assertThat(list.totalOutstandingActive).isEqualByComparingTo("2500.00")
        assertThat(list.top.map { it.borrowerName }).containsExactly("Carol", "Bob")
    }

    @Test
    fun `compound lending requires compounding period`() {
        val (user, household) = seed()
        assertThatThrownBy {
            service.create(
                household.id,
                LendingRequest(
                    borrowerName = "Dan",
                    principalAmount = BigDecimal("100.00"),
                    startDate = LocalDate.of(2025, 1, 1),
                    interestType = InterestType.compound,
                    annualInterestRate = BigDecimal("5"),
                ),
                user,
            )
        }.hasMessageContaining("LENDING_COMPOUNDING_REQUIRED")
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "ln${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
