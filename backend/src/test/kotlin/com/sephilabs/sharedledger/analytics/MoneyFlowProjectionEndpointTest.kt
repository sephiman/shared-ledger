package com.sephilabs.sharedledger.analytics

import tools.jackson.databind.ObjectMapper
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

class MoneyFlowProjectionEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
) : AuthHttpTestBase() {

    @Test
    fun `returns the current month projection with its window`() {
        val session = sessionOf(register(newEmail("projection")))
        val today = LocalDate.now()

        mockMvc.perform(get("/api/households/${householdOf(session)}/analytics/money-flow/projection")
            .param("level", "category").param("windowMonths", "6").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.year").value(today.year))
            .andExpect(jsonPath("$.month").value(today.monthValue))
            .andExpect(jsonPath("$.level").value("category"))
            .andExpect(jsonPath("$.windowMonths").value(6))
            .andExpect(jsonPath("$.income").value("0.00"))
            .andExpect(jsonPath("$.components").isArray)
    }

    @Test
    fun `returns 400 for an unknown breakdown level`() {
        val session = sessionOf(register(newEmail("projection-bad")))

        mockMvc.perform(get("/api/households/${householdOf(session)}/analytics/money-flow/projection")
            .param("level", "leaf").cookie(session))
            .andExpect(status().isBadRequest)
    }

    private fun householdOf(session: Cookie): String =
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString()
}
