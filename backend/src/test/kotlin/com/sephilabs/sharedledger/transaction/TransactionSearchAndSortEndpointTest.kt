package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.catalog.CustomCategoryWriter
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TransactionSearchAndSortEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val transactions: TransactionRepository,
    private val customCategories: CustomCategoryWriter,
    private val users: UserRepository,
) : AuthHttpTestBase() {

    private val day = LocalDate.of(2026, 4, 15)

    @Test
    fun `filters by amountFrom and amountTo by magnitude across list, totals, and csv export`() {
        val (session, household, user) = household("amount-range")
        seed(household, user, "10.00", description = "Coffee")
        seed(household, user, "25.00", description = "Lunch")
        seed(household, user, "-30.00", refund = true, description = "Shirt refund")
        seed(household, user, "50.00", direction = Direction.income, category = "income.salary", description = "Bonus")
        seed(household, user, "120.00", description = "Train")

        list(session, household, "amountFrom=25&amountTo=50")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(3))

        val descriptions = descriptionsOf(list(session, household, "amountFrom=25&amountTo=50"))
        assertThat(descriptions).containsExactlyInAnyOrder("Lunch", "Shirt refund", "Bonus")

        // Totals match the same filtered subset.
        mockMvc.perform(get("/api/households/$household/transactions/totals?amountFrom=25&amountTo=50").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(3))
            .andExpect(jsonPath("$.income").value("50.00"))
            .andExpect(jsonPath("$.expenses").value("-5.00"))
            .andExpect(jsonPath("$.net").value("55.00"))

        // Export CSV applies the same amount bounds.
        val csv = mockMvc.perform(get("/api/households/$household/transactions/export.csv?amountFrom=25&amountTo=50").cookie(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(csv).contains("Lunch", "Shirt refund", "Bonus")
        assertThat(csv).doesNotContain("Coffee", "Train")

        // Lower bound only and upper bound only.
        assertThat(descriptionsOf(list(session, household, "amountFrom=50")))
            .containsExactlyInAnyOrder("Bonus", "Train")
        assertThat(descriptionsOf(list(session, household, "amountTo=25")))
            .containsExactlyInAnyOrder("Coffee", "Lunch")
    }

    @Test
    fun `sorts by date, signed amount, and description with blanks last`() {
        val (session, household, user) = household("sort-fields")
        seed(household, user, "100.00", description = "zara", date = day.minusDays(2))
        seed(household, user, "12.50", description = "Alpha", date = day.minusDays(1))
        seed(household, user, "-20.00", refund = true, description = null, date = day)
        seed(household, user, "2400.00", direction = Direction.income, category = "income.salary", description = "  ", date = day.minusDays(3))

        // Amount sort uses signed display convention: +2400 > +20 (refund) > -12.50 > -100.00
        assertThat(amountsOf(list(session, household, "sort=amount_desc")))
            .containsExactly("2400.00", "-20.00", "12.50", "100.00")
        assertThat(amountsOf(list(session, household, "sort=amount_asc")))
            .containsExactly("100.00", "12.50", "-20.00", "2400.00")

        // Date sort: newest first by default, oldest first for date_asc.
        assertThat(amountsOf(list(session, household, "sort=date_desc")))
            .containsExactly("-20.00", "12.50", "100.00", "2400.00")
        assertThat(amountsOf(list(session, household, "sort=date_asc")))
            .containsExactly("2400.00", "100.00", "12.50", "-20.00")

        // Description sort: case-insensitive, null/blank descriptions last in both directions.
        assertThat(descriptionsOf(list(session, household, "sort=description_asc")))
            .containsExactly("Alpha", "zara", null, "  ")
        assertThat(descriptionsOf(list(session, household, "sort=description_desc")))
            .containsExactly("zara", "Alpha", null, "  ")

        // Pagination respects sort order across pages.
        assertThat(amountsOf(list(session, household, "sort=amount_desc&size=2&page=0")))
            .containsExactly("2400.00", "-20.00")
        assertThat(amountsOf(list(session, household, "sort=amount_desc&size=2&page=1")))
            .containsExactly("12.50", "100.00")
    }

    @Test
    fun `sorts by localized category name including custom categories`() {
        val (session, household, userId) = household("sort-category")
        val owner = users.findById(userId).orElseThrow()
        val custom = customCategories.create(household, "Books", "expense", "outings", false, owner)
        // EN: Books (custom), Fuel (transport.fuel), Groceries (groceries.groceries), Restaurants & bars (outings.restaurants)
        // ES: Alimentación (groceries.groceries), Books (custom), Combustible (transport.fuel), Restaurantes y bares (outings.restaurants)
        seed(household, userId, "10.00", category = "outings.restaurants")
        seed(household, userId, "20.00", category = "groceries.groceries")
        seed(household, userId, "30.00", category = "transport.fuel")
        seed(household, userId, "40.00", category = custom.id.code)

        assertThat(categoriesOf(list(session, household, "sort=category_asc&locale=en")))
            .containsExactly(custom.id.code, "transport.fuel", "groceries.groceries", "outings.restaurants")
        assertThat(categoriesOf(list(session, household, "sort=category_desc&locale=en")))
            .containsExactly("outings.restaurants", "groceries.groceries", "transport.fuel", custom.id.code)

        assertThat(categoriesOf(list(session, household, "sort=category_asc&locale=es")))
            .containsExactly("groceries.groceries", custom.id.code, "transport.fuel", "outings.restaurants")
    }

    private fun list(session: Cookie, household: UUID, query: String): ResultActions =
        mockMvc.perform(get("/api/households/$household/transactions?$query").cookie(session))

    private fun itemsOf(result: ResultActions): List<JsonNode> =
        objectMapper.readTree(result.andReturn().response.contentAsString)["items"].toList()

    private fun descriptionsOf(result: ResultActions): List<String?> =
        itemsOf(result).map { if (it["description"].isNull) null else it["description"].asString() }

    private fun amountsOf(result: ResultActions): List<String> =
        itemsOf(result).map { it["amount"].asString() }

    private fun categoriesOf(result: ResultActions): List<String> =
        itemsOf(result).map { it["categoryCode"].asString() }

    private fun seed(
        household: UUID,
        user: UUID,
        amount: String,
        refund: Boolean = false,
        direction: Direction = Direction.expense,
        category: String = "outings.restaurants",
        description: String? = null,
        date: LocalDate = day,
    ) {
        transactions.save(
            Transaction(
                householdId = household,
                occurrenceDate = date,
                direction = direction,
                categoryCode = category,
                amount = BigDecimal(amount),
                description = description,
                createdByUserId = user,
                updatedByUserId = user,
            ).apply { isRefund = refund },
        )
    }

    private fun household(tag: String): Triple<Cookie, UUID, UUID> {
        val session = sessionOf(register(newEmail(tag)))
        val me = objectMapper.readTree(me(session).andReturn().response.contentAsString)
        return Triple(
            session,
            UUID.fromString(me["households"][0]["householdId"].asString()),
            UUID.fromString(me["id"].asString()),
        )
    }
}
