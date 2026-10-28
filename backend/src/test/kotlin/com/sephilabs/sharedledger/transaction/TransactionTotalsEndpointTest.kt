package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The list's totals line: the whole filtered set, whatever page is on screen, under the list's own filters. */
class TransactionTotalsEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val transactions: TransactionRepository,
) : AuthHttpTestBase() {

    private val day = LocalDate.of(2026, 3, 10)

    @Test
    fun `sums the whole filtered set, not the page on screen`() {
        val (session, household, user) = household("totals-pages")
        repeat(120) { seed(household, user, "1.25") }

        totals(session, household, "direction=expense&size=50&page=1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(120))
            .andExpect(jsonPath("$.expenses").value("150.00"))
            .andExpect(jsonPath("$.income").value("0.00"))
    }

    @Test
    fun `refunds net against expenses, and with refunds only expenses are what came back, stored negative like refunds`() {
        val (session, household, user) = household("totals-refunds")
        seed(household, user, "100.00")
        seed(household, user, "-30.00", refund = true)
        seed(household, user, "-5.50", refund = true)

        totals(session, household, "direction=expense")
            .andExpect(jsonPath("$.count").value(3))
            .andExpect(jsonPath("$.expenses").value("64.50"))
            .andExpect(jsonPath("$.refunded").doesNotExist())
        totals(session, household, "direction=expense&isRefund=true")
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.expenses").value("-35.50"))
    }

    @Test
    fun `each direction alone, and all directions with income, expenses and net`() {
        val (session, household, user) = household("totals-directions")
        seed(household, user, "2400.00", direction = Direction.income, category = "income.salary")
        seed(household, user, "80.00")
        seed(household, user, "20.00")

        totals(session, household, "direction=income")
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.income").value("2400.00"))
            .andExpect(jsonPath("$.expenses").value("0.00"))
        totals(session, household, "direction=expense")
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.expenses").value("100.00"))
        totals(session, household, "")
            .andExpect(jsonPath("$.count").value(3))
            .andExpect(jsonPath("$.income").value("2400.00"))
            .andExpect(jsonPath("$.expenses").value("100.00"))
            .andExpect(jsonPath("$.net").value("2300.00"))
    }

    @Test
    fun `the list's other filters apply too — dates, category and group`() {
        val (session, household, user) = household("totals-filters")
        seed(household, user, "10.00", category = "outings.restaurants")
        seed(household, user, "15.00", category = "outings.travel")
        seed(household, user, "40.00", category = "groceries.groceries")
        seed(household, user, "99.00", category = "outings.restaurants", date = day.minusYears(1))

        totals(session, household, "categoryGroup=outings&from=2026-01-01&to=2026-12-31")
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.expenses").value("25.00"))
        totals(session, household, "categoryCode=outings.restaurants")
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.expenses").value("109.00"))
    }

    @Test
    fun `an empty result is zero everywhere`() {
        val (session, household, _) = household("totals-empty")

        totals(session, household, "direction=expense&from=2030-01-01")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.income").value("0.00"))
            .andExpect(jsonPath("$.expenses").value("0.00"))
            .andExpect(jsonPath("$.net").value("0.00"))
    }

    @Test
    fun `another household's totals are out of reach`() {
        val (_, victim, _) = household("totals-victim")
        val intruder = sessionOf(register(newEmail("totals-intruder")))

        totals(intruder, victim, "").andExpect(status().isForbidden)
    }

    private fun totals(session: Cookie, household: UUID, query: String): ResultActions =
        mockMvc.perform(get("/api/households/$household/transactions/totals?$query").cookie(session))

    private fun seed(
        household: UUID,
        user: UUID,
        amount: String,
        refund: Boolean = false,
        direction: Direction = Direction.expense,
        category: String = "outings.restaurants",
        date: LocalDate = day,
    ) {
        transactions.save(
            Transaction(
                householdId = household, occurrenceDate = date, direction = direction, categoryCode = category,
                amount = BigDecimal(amount), description = null, createdByUserId = user, updatedByUserId = user,
            ).apply { isRefund = refund },
        )
    }

    private fun household(tag: String): Triple<Cookie, UUID, UUID> {
        val session = sessionOf(register(newEmail(tag)))
        val me = objectMapper.readTree(me(session).andReturn().response.contentAsString)
        return Triple(session, UUID.fromString(me["households"][0]["householdId"].asString()), UUID.fromString(me["id"].asString()))
    }
}
