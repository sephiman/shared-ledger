package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class RecurringMaterializerIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val templates: RecurringTemplateRepository,
    private val transactions: TransactionRepository,
    private val materializer: RecurringMaterializer,
    private val service: RecurringService,
) : IntegrationTestBase() {

    @Test
    fun `new template with backdated start does not back-fill past periods`() {
        val user = users.save(User(email = "nb${System.nanoTime()}@example.com", passwordHash = "x"))
        val household = households.save(Household(name = "H", currency = "EUR"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))

        val today = LocalDate.now()
        // Pick a dayOfMonth that cannot equal today, so the first nightly run finds
        // zero in-range occurrences for the [today, today] window.
        val differentDay: Short = if (today.dayOfMonth == 1) 2 else 1
        val template = service.create(
            household.id,
            RecurringTemplateRequest(
                direction = Direction.expense,
                categoryCode = "home.rent",
                amount = BigDecimal("1000.00"),
                cadence = Cadence.monthly,
                dayOfMonth = differentDay,
                startDate = today.minusYears(1),
            ),
            user,
        )

        val created = materializer.runForHousehold(household.id, today)

        assertThat(created).isEqualTo(0)
        assertThat(
            transactions.findByHouseholdIdAndOccurrenceDateBetween(
                household.id, today.minusYears(1), today,
            )
        ).isEmpty()
        assertThat(templates.findById(template.id).orElseThrow().lastMaterializedThrough)
            .isEqualTo(today)
    }

    @Test
    fun `materializer is idempotent across reruns`() {
        val user = users.save(User(email = "tester@example.com", passwordHash = "x"))
        val household = households.save(Household(name = "H", currency = "EUR"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))

        val template = templates.save(
            RecurringTemplate(
                householdId = household.id,
                direction = Direction.expense,
                categoryCode = "home.rent",
                amount = BigDecimal("1000.00"),
                cadence = Cadence.monthly,
                dayOfMonth = 1,
                startDate = LocalDate.of(2025, 1, 1),
                createdByUserId = user.id,
                updatedByUserId = user.id,
            )
        )
        // Anchor watermark just before the start date so catch-up covers the test range
        // deterministically (the null-watermark fallback uses updatedAt = real-now).
        templates.findById(template.id).orElseThrow().apply {
            lastMaterializedThrough = LocalDate.of(2024, 12, 31)
            templates.save(this)
        }

        materializer.runForHousehold(household.id, LocalDate.of(2025, 4, 15))
        val first = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 5, 1)).size

        materializer.runForHousehold(household.id, LocalDate.of(2025, 4, 15))
        val second = transactions.findByHouseholdIdAndOccurrenceDateBetween(household.id, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 5, 1)).size

        assertThat(first).isEqualTo(4) // Jan, Feb, Mar, Apr
        assertThat(second).isEqualTo(first)

        val refreshed = templates.findById(template.id).orElseThrow()
        assertThat(refreshed.lastMaterializedThrough).isEqualTo(LocalDate.of(2025, 4, 15))
    }
}
