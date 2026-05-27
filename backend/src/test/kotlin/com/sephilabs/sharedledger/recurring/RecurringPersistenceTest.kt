package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class RecurringPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: RecurringService,
    private val repo: RecurringTemplateRepository,
    private val transactions: TransactionRepository,
) : IntegrationTestBase() {

    @Test
    fun `update persists amount`() {
        val (user, household) = seed()
        val template = service.create(household.id, sampleRequest(amount = "1000.00"), user)

        service.update(household.id, template.id, sampleRequest(amount = "1500.00"), user)

        val reloaded = repo.findById(template.id).orElseThrow()
        assertThat(reloaded.amount).isEqualByComparingTo("1500.00")
        assertThat(reloaded.updatedByUserId).isEqualTo(user.id)
    }

    @Test
    fun `delete marks deletedAt`() {
        val (user, household) = seed()
        val template = service.create(household.id, sampleRequest(), user)

        service.delete(household.id, template.id, user)

        val hidden = repo.findAll().find { it.id == template.id }
        assertThat(hidden).isNull()
    }

    @Test
    fun `fireNow creates a transaction dated today regardless of cadence`() {
        val (user, household) = seed()
        // Template starts today with cadence "monthly on day 1" — today isn't necessarily the 1st.
        // Force-fire should still create today's row.
        val template = service.create(
            household.id,
            sampleRequest(amount = "1000.00", startDate = LocalDate.now()),
            user,
        )

        val created = service.fireNow(household.id, template.id, user)

        assertThat(created).isGreaterThanOrEqualTo(1)
        val today = LocalDate.now()
        assertThat(transactions.existsByRecurringTemplateIdAndOccurrenceDate(template.id, today)).isTrue
    }

    @Test
    fun `fireNow advances lastMaterializedThrough to today`() {
        val (user, household) = seed()
        val template = service.create(
            household.id,
            sampleRequest(startDate = LocalDate.now()),
            user,
        )
        assertThat(repo.findById(template.id).orElseThrow().lastMaterializedThrough).isNull()

        service.fireNow(household.id, template.id, user)

        val reloaded = repo.findById(template.id).orElseThrow()
        assertThat(reloaded.lastMaterializedThrough).isEqualTo(LocalDate.now())
    }

    @Test
    fun `fireNow catches up missed cadence occurrences before firing today`() {
        val (user, household) = seed()
        // Template that started 4 months before today, monthly on the 1st, never materialized.
        // fireNow should catch up the missed 1sts AND fire today.
        val fourMonthsAgo = LocalDate.now().minusMonths(4).withDayOfMonth(1)
        val template = service.create(
            household.id,
            sampleRequest(startDate = fourMonthsAgo).copy(dayOfMonth = 1),
            user,
        )

        val created = service.fireNow(household.id, template.id, user)

        val today = LocalDate.now()
        // We should have at least the 4 catch-up "1st" dates + today (5 total), unless today IS the 1st
        // in which case the catch-up loop already includes today and we get 5 distinct dates anyway.
        assertThat(created).isGreaterThanOrEqualTo(4)
        assertThat(transactions.existsByRecurringTemplateIdAndOccurrenceDate(template.id, today)).isTrue
        assertThat(repo.findById(template.id).orElseThrow().lastMaterializedThrough).isEqualTo(today)
    }

    @Test
    fun `fireNow is idempotent on the same day`() {
        val (user, household) = seed()
        val template = service.create(
            household.id,
            sampleRequest(startDate = LocalDate.now()),
            user,
        )

        val first = service.fireNow(household.id, template.id, user)
        val second = service.fireNow(household.id, template.id, user)

        assertThat(first).isGreaterThanOrEqualTo(1)
        assertThat(second).isEqualTo(0) // no new rows, marker stays at today
        val countToday = transactions.findByHouseholdIdAndOccurrenceDateBetween(
            household.id, LocalDate.now(), LocalDate.now(),
        ).count { it.recurringTemplateId == template.id }
        assertThat(countToday).isEqualTo(1)
    }

    private fun sampleRequest(
        amount: String = "1000.00",
        startDate: LocalDate = LocalDate.of(2025, 1, 1),
    ) = RecurringTemplateRequest(
        direction = Direction.expense,
        categoryCode = "home.rent",
        amount = BigDecimal(amount),
        description = null,
        cadence = Cadence.monthly,
        dayOfMonth = 1,
        startDate = startDate,
        active = true,
    )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "rt${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
