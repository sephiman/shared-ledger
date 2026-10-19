package com.sephilabs.sharedledger.config

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.recurring.RecurringTemplateRequest
import com.sephilabs.sharedledger.transaction.TransactionRequest
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

/** Request bodies go through the API's own mapper: an explicit null on a field with a Kotlin default means the
 *  field is absent, a required field that is missing or null is a 400 naming it, and nothing client-shaped is a 500. */
class RequestBodyBindingIntegrationTest @Autowired constructor(
    private val mapper: ObjectMapper,
) : AuthHttpTestBase() {

    private lateinit var session: Cookie
    private lateinit var householdId: String

    @BeforeEach
    fun signIn() {
        val email = newEmail("binding")
        register(email)
        session = sessionOf(login(email, PASSWORD).andExpect(status().isOk).andReturn())
        householdId = mapper.readTree(me(session).andReturn().response.contentAsString)
            .get("households").get(0).get("householdId").asString()
    }

    @Test
    fun `the API mapper treats an explicit null as the field's default`() {
        // Pinned on the mapper itself so a Boot or Jackson upgrade can't silently drop null-means-absent. A `true`
        // default is the telling case: a primitive null quietly read as false would also "succeed".
        val template = mapper.readValue(
            """{ "direction": "expense", "categoryCode": "home.utilities", "amount": "80.00", "cadence": "monthly",
                 "startDate": "2026-10-28", "active": null }""",
            RecurringTemplateRequest::class.java,
        )
        val transaction = mapper.readValue(
            """{ "occurrenceDate": "2026-10-01", "direction": "expense", "categoryCode": "groceries.groceries",
                 "amount": "12.50", "isRefund": null }""",
            TransactionRequest::class.java,
        )

        assertThat(template.active).isTrue()
        assertThat(transaction.isRefund).isFalse()
    }

    @Test
    fun `creates a transaction when isRefund is null`() {
        postJson("transactions", transaction(""", "isRefund": null"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.isRefund").value(false))
    }

    @Test
    fun `creates a transaction when isRefund is omitted`() {
        postJson("transactions", transaction(""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.isRefund").value(false))
    }

    @Test
    fun `creates an active recurring template when active is null`() {
        postJson(
            "recurring-templates",
            """{ "direction": "expense", "categoryCode": "home.utilities", "amount": "80.00", "cadence": "monthly",
                 "dayOfMonth": 28, "startDate": "2026-10-28", "active": null }""",
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.active").value(true))
    }

    @Test
    fun `creates a non-essential custom category when essential is null`() {
        postJson("categories", """{ "name": "Board games", "kind": "expense", "groupCode": "outings", "essential": null }""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.essential").value(false))
    }

    @Test
    fun `logs in when rememberMe is null`() {
        val email = newEmail("remember-null")
        register(email)

        mockMvc.perform(
            post("/api/auth/login").with(csrf()).with(from(uniqueIp()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email", "password": "$PASSWORD", "rememberMe": null }"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `returns 400 naming a required field sent as null`() {
        postJson("transactions", transaction("").replace("\"2026-10-01\"", "null"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.args[0]").value("occurrenceDate"))
            .andExpect(jsonPath("$.fields.occurrenceDate").exists())
    }

    @Test
    fun `returns 400 naming a required field that is missing`() {
        postJson("transactions", """{ "direction": "expense", "categoryCode": "groceries.groceries", "amount": "12.50" }""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.fields.occurrenceDate").exists())
    }

    @Test
    fun `returns 400 naming a field of the wrong type`() {
        postJson("transactions", transaction("").replace("\"12.50\"", "\"abc\""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.message").value(containsString("amount")))
            .andExpect(jsonPath("$.fields.amount").exists())
    }

    @Test
    fun `returns 400 naming the full path of a bad field inside a list`() {
        mockMvc.perform(
            put("/api/households/$householdId/budgets").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "items": [ { "year": "next", "categoryCode": "groceries.groceries", "amount": "300.00" } ] }"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.args[0]").value("items[0].year"))
    }

    @Test
    fun `returns 400 for a body that is not JSON`() {
        postJson("transactions", """{ "occurrenceDate": "2026-10-01", """)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_BODY"))
    }

    private fun transaction(extra: String) =
        """{ "occurrenceDate": "2026-10-01", "direction": "expense", "categoryCode": "groceries.groceries",
             "amount": "12.50"$extra }"""

    private fun postJson(path: String, body: String): ResultActions =
        mockMvc.perform(
            post("/api/households/$householdId/$path").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
}
