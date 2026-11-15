package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class BankCashEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val adjustments: CashAdjustmentRepository,
) : AuthHttpTestBase() {

    @Test
    fun `returns 409 when editing an adjustment written by the bank`() {
        val session = sessionOf(register(newEmail("bank-anchor")))
        val household = householdOf(session)
        val anchor = adjustments.save(
            CashAdjustment(
                householdId = household,
                adjustmentDate = LocalDate.now(),
                amount = BigDecimal("100.00"),
                createdByUserId = null,
                updatedByUserId = null,
                source = CashAdjustmentSource.bank,
            ),
        )

        mockMvc.perform(
            patch("/api/households/$household/cash/adjustments/${anchor.id}").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"adjustmentDate":"${LocalDate.now()}","amount":"5.00"}"""),
        ).andExpect(status().isConflict)

        mockMvc.perform(get("/api/households/$household/cash/adjustments").cookie(session))
            .andExpect(jsonPath("$[0].source").value("bank"))
            .andExpect(jsonPath("$[0].amount").value("100.00"))
    }

    @Test
    fun `other cash is stored and reported in the bank cash total`() {
        val session = sessionOf(register(newEmail("other-cash")))
        val household = householdOf(session)

        mockMvc.perform(
            put("/api/households/$household/cash/other-cash").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":"250.5"}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/households/$household/cash/bank").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accounts").isEmpty)
            .andExpect(jsonPath("$.bankTotal").value("0.00"))
            .andExpect(jsonPath("$.otherCash").value("250.50"))
            .andExpect(jsonPath("$.total").value("250.50"))
    }

    private fun householdOf(session: Cookie): UUID =
        UUID.fromString(objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
}
