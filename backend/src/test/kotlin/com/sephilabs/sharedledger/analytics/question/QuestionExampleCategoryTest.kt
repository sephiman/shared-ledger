package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.AI_FEATURES_DISABLED
import com.sephilabs.sharedledger.bank.aisuggestion.TogglableAiAvailability
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The Ask box's example names the household's own most used expense category. */
@ResourceLock("ai-doubles")
class QuestionExampleCategoryTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val availability: TogglableAiAvailability,
    private val transactions: TransactionRepository,
    private val categories: CategoryService,
) : AuthHttpTestBase() {

    private val today = LocalDate.now()

    @AfterEach
    fun restore() {
        availability.forced = null
    }

    @Test
    fun `is the expense category with the most confirmed transactions in the last 12 months`() {
        val (owner, household, userId) = householdWithAiOn("example-top")
        repeat(3) { seed(household, userId, "outings.restaurants", today.minusMonths(2)) }
        repeat(2) { seed(household, userId, "groceries.groceries", today.minusDays(3)) }
        // Older than 12 months, refunds and income do not count, however many there are.
        repeat(6) { seed(household, userId, "outings.travel", today.minusMonths(13)) }
        repeat(6) { seed(household, userId, "groceries.groceries", today.minusDays(1), refund = true) }
        repeat(6) { seed(household, userId, "income.salary", today.minusDays(1), direction = Direction.income) }

        mockMvc.perform(get(path(household)).cookie(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categoryCode").value("outings.restaurants"))
    }

    @Test
    fun `falls back to the first expense category in catalogue order when nothing was spent`() {
        val (owner, household, _) = householdWithAiOn("example-empty")
        val first = categories.listForHousehold(household).first { it.kind == Direction.expense.name }.code

        mockMvc.perform(get(path(household)).cookie(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categoryCode").value(first))
    }

    @Test
    fun `is gated like every AI feature`() {
        val owner = sessionOf(register(newEmail("example-off")))
        val household = householdOf(owner)

        mockMvc.perform(get(path(household)).cookie(owner))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value(AI_FEATURES_DISABLED))
        availability.forced = false
        mockMvc.perform(get(path(household)).cookie(owner)).andExpect(status().isNotFound)
    }

    private fun path(household: UUID) = "/api/households/$household/questions/example-category"

    private fun seed(
        household: UUID,
        userId: UUID,
        category: String,
        date: LocalDate,
        refund: Boolean = false,
        direction: Direction = Direction.expense,
    ) {
        transactions.save(
            Transaction(
                householdId = household, occurrenceDate = date, direction = direction, categoryCode = category,
                amount = BigDecimal(if (refund) "-10.00" else "10.00"), description = null,
                createdByUserId = userId, updatedByUserId = userId,
            ).apply { isRefund = refund },
        )
    }

    private fun householdWithAiOn(tag: String): Triple<Cookie, UUID, UUID> {
        val owner = sessionOf(register(newEmail(tag)))
        val household = householdOf(owner)
        mockMvc.perform(
            put("/api/households/$household/ai-suggestions/settings").with(csrf()).cookie(owner)
                .contentType(MediaType.APPLICATION_JSON).content("""{"enabled":true}"""),
        ).andExpect(status().isOk)
        val userId = UUID.fromString(objectMapper.readTree(me(owner).andReturn().response.contentAsString)["id"].asString())
        return Triple(owner, household, userId)
    }

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
    )
}
