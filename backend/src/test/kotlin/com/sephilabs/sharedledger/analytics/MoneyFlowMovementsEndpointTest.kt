package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.SqlRecorder
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.movement.MovementRequest
import com.sephilabs.sharedledger.networth.movement.MovementService
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MoneyFlowMovementsEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val transactions: TransactionService,
    private val movements: MovementService,
    private val users: UserRepository,
) : AuthHttpTestBase() {

    @Test
    fun `with the option off the response is byte-identical to the diagram without contributions and withdrawals`() {
        val (session, householdId) = seededHousehold()

        val omitted = moneyFlow(session, householdId, null)
        val off = moneyFlow(session, householdId, false)

        // Captured from the endpoint before the option existed, on this same data.
        assertThat(omitted).isEqualTo(
            """{"from":"2025-03-01","to":"2025-03-31","level":"group","income":"1000.00","expenses":"400.00",""" +
                """"saved":"600.00","deficit":"0.00","nodes":[""" +
                """{"id":"income.salary","side":"income","groupCode":null,"amount":"1000.00"},""" +
                """{"id":"hub","side":"hub","groupCode":null,"amount":"1000.00"},""" +
                """{"id":"home","side":"expense","groupCode":"home","amount":"400.00"},""" +
                """{"id":"saved","side":"saved","groupCode":null,"amount":"600.00"}],"links":[""" +
                """{"source":"income.salary","target":"hub","amount":"1000.00"},""" +
                """{"source":"hub","target":"home","amount":"400.00"},""" +
                """{"source":"hub","target":"saved","amount":"600.00"}]}""",
        )
        assertThat(off).isEqualTo(omitted)
    }

    @Test
    fun `with the option off a group refunds turned net negative is pinned as a net refund source`() {
        val (session, householdId) = seededHousehold()
        val user = users.findByEmailIgnoreCase(emailOf(session))!!
        tx(householdId, user, LocalDate.of(2025, 5, 1), Direction.income, "income.salary", "1000.00")
        tx(householdId, user, LocalDate.of(2025, 5, 3), Direction.expense, "home.rent", "400.00")
        tx(householdId, user, LocalDate.of(2025, 5, 4), Direction.expense, "outings.restaurants", "20.00")
        transactions.create(householdId, TransactionRequest(
            occurrenceDate = LocalDate.of(2025, 5, 9), direction = Direction.expense, categoryCode = "outings.restaurants",
            amount = BigDecimal("-70.00"), description = null, isRefund = true,
        ), user)

        val off = moneyFlow(session, householdId, false, from = "2025-05-01", to = "2025-05-31")

        // Deliberate change (the pre-existing imbalance): before, "outings" was dropped and the hub (1000) was
        // smaller than its outflows (400 + 650). Now its net refund feeds the hub and both sides read 1050.
        assertThat(off).isEqualTo(
            """{"from":"2025-05-01","to":"2025-05-31","level":"group","income":"1000.00","expenses":"350.00",""" +
                """"saved":"650.00","deficit":"0.00","nodes":[""" +
                """{"id":"income.salary","side":"income","groupCode":null,"amount":"1000.00"},""" +
                """{"id":"net_refund:outings","side":"net_refund","groupCode":"outings","amount":"50.00"},""" +
                """{"id":"hub","side":"hub","groupCode":null,"amount":"1050.00"},""" +
                """{"id":"home","side":"expense","groupCode":"home","amount":"400.00"},""" +
                """{"id":"saved","side":"saved","groupCode":null,"amount":"650.00"}],"links":[""" +
                """{"source":"income.salary","target":"hub","amount":"1000.00"},""" +
                """{"source":"net_refund:outings","target":"hub","amount":"50.00"},""" +
                """{"source":"hub","target":"home","amount":"400.00"},""" +
                """{"source":"hub","target":"saved","amount":"650.00"}]}""",
        )
    }

    @Test
    fun `with the option off no movements query is executed`() {
        val (session, householdId) = seededHousehold()

        val (_, offSql) = SqlRecorder.record { moneyFlow(session, householdId, false) }
        val (_, onSql) = SqlRecorder.record { moneyFlow(session, householdId, true) }

        assertThat(offSql).isNotEmpty.noneMatch { it.contains("net_worth_movements") }
        assertThat(onSql).anyMatch { it.contains("net_worth_movements") }
    }

    @Test
    fun `with the option on the totals equal Wealth flows for the same period and classes`() {
        val (session, householdId) = seededHousehold()

        val flow = objectMapper.readTree(moneyFlow(session, householdId, true))
        val cumulative = objectMapper.readTree(
            mockMvc.perform(get("/api/households/$householdId/movements/cumulative")
                .param("from", "2025-03-01").param("to", "2025-03-31").param("groupBy", "asset_class").cookie(session))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        )
        val classBuckets = cumulative.filter { it["key"].asString() != "cash" && it["key"].asString() != "liabilities" }

        val movements = flow["movements"]
        assertThat(BigDecimal(movements["contributed"].asString()))
            .isEqualByComparingTo(classBuckets.sumOf { BigDecimal(it["contributions"].asString()) })
        assertThat(BigDecimal(movements["withdrawn"].asString()))
            .isEqualByComparingTo(classBuckets.sumOf { BigDecimal(it["withdrawals"].asString()) })
        for (bucket in classBuckets) {
            val code = bucket["key"].asString()
            val contributed = movements["classes"].filter { it["assetClassCode"].asString() == code && it["direction"].asString() == "contribution" }
                .sumOf { BigDecimal(it["amount"].asString()) }
            val withdrawn = movements["classes"].filter { it["assetClassCode"].asString() == code && it["direction"].asString() == "withdrawal" }
                .sumOf { BigDecimal(it["amount"].asString()) }
            assertThat(contributed).`as`("contributed to $code").isEqualByComparingTo(BigDecimal(bucket["contributions"].asString()))
            assertThat(withdrawn).`as`("withdrawn from $code").isEqualByComparingTo(BigDecimal(bucket["withdrawals"].asString()))
        }
        // Income, expenses, saved and deficit keep their meaning with the option on.
        assertThat(flow["income"].asString()).isEqualTo("1000.00")
        assertThat(flow["expenses"].asString()).isEqualTo("400.00")
        assertThat(flow["saved"].asString()).isEqualTo("600.00")
        assertThat(flow["deficit"].asString()).isEqualTo("0.00")
    }

    private fun moneyFlow(
        session: Cookie,
        householdId: UUID,
        movements: Boolean?,
        from: String = "2025-03-01",
        to: String = "2025-03-31",
    ): String {
        val request = get("/api/households/$householdId/analytics/money-flow")
            .param("from", from).param("to", to).param("level", "group").cookie(session)
        if (movements != null) request.param("movements", movements.toString())
        return mockMvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
    }

    private fun emailOf(session: Cookie): String =
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["email"].asString()

    private fun seededHousehold(): Pair<Cookie, UUID> {
        val email = newEmail("mf-movements")
        val session = sessionOf(register(email))
        val householdId = UUID.fromString(
            objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
        )
        val user = users.findByEmailIgnoreCase(email)!!
        tx(householdId, user, LocalDate.of(2025, 3, 1), Direction.income, "income.salary", "1000.00")
        tx(householdId, user, LocalDate.of(2025, 3, 5), Direction.expense, "home.rent", "400.00")
        movement(householdId, user, LocalDate.of(2025, 3, 10), MovementType.contribution, "etfs", "300.00")
        movement(householdId, user, LocalDate.of(2025, 3, 12), MovementType.contribution, "crypto", "50.00")
        movement(householdId, user, LocalDate.of(2025, 3, 20), MovementType.withdrawal, "crypto", "20.00")
        movement(householdId, user, LocalDate.of(2025, 3, 21), MovementType.contribution, "cash", "999.00")
        // Outside the period: never counted.
        movement(householdId, user, LocalDate.of(2025, 4, 1), MovementType.contribution, "etfs", "700.00")
        return session to householdId
    }

    private fun tx(householdId: UUID, user: User, date: LocalDate, direction: Direction, category: String, amount: String) {
        transactions.create(householdId, TransactionRequest(
            occurrenceDate = date, direction = direction, categoryCode = category, amount = BigDecimal(amount), description = null,
        ), user)
    }

    private fun movement(householdId: UUID, user: User, date: LocalDate, type: MovementType, assetClass: String, amount: String) {
        movements.create(householdId, MovementRequest(
            movementDate = date, type = type, assetClassCode = assetClass, amount = BigDecimal(amount),
        ), user)
    }
}
