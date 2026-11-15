package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.bank.aisuggestion.ScriptedSuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.TogglableAiAvailability
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

@ResourceLock("ai-doubles")
class FireQuestionEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val availability: TogglableAiAvailability,
    private val model: ScriptedSuggestionModel,
) : AuthHttpTestBase() {

    @BeforeEach
    fun reset() {
        availability.forced = null
        model.reset()
    }

    @AfterEach
    fun restore() {
        availability.forced = null
        model.reset()
    }

    private fun householdWithAiOn(tag: String): Pair<Cookie, UUID> {
        val owner = sessionOf(register(newEmail(tag)))
        val household = UUID.fromString(
            objectMapper.readTree(me(owner).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
        )
        mockMvc.perform(
            put("/api/households/$household/ai-suggestions/settings")
                .cookie(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true}"""),
        ).andExpect(status().isOk)
        return owner to household
    }

    @Test
    fun `translates natural-language FIRE what-if without sending household amounts`() {
        val (owner, householdId) = householdWithAiOn("fire-q")
        val nextFiveYears = LocalDate.now().year + 5

        model.responder = {
            ScriptedSuggestionModel.answer(
                """
                {
                  "whatIf": {
                    "monthlyContribution": {"kind": "delta", "value": 250},
                    "monthlySpending": {"kind": "percent", "value": -10},
                    "targetYear": {"kind": "delta", "value": -2},
                    "safeWithdrawalRatePct": null,
                    "lumpSum": 5000,
                    "returnShiftPct": null,
                    "cashFlowEvents": [
                      {
                        "year": null,
                        "yearsFromNow": 5,
                        "kind": "lump_sum",
                        "amount": 30000,
                        "label": "Bonus windfall",
                        "template": "windfall"
                      }
                    ]
                  },
                  "unsupported": [],
                  "confidence": 0.92
                }
                """.trimIndent(),
            )
        }

        mockMvc.perform(
            post("/api/households/$householdId/fire/question")
                .cookie(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"Save 250 more, spend 10% less, retire 2 years earlier, put 5k now and 30k windfall in 5 years","language":"en"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.understood").value(true))
            .andExpect(jsonPath("$.whatIf.monthlyContribution.kind").value("delta"))
            .andExpect(jsonPath("$.whatIf.monthlyContribution.value").value("250"))
            .andExpect(jsonPath("$.whatIf.monthlySpending.kind").value("percent"))
            .andExpect(jsonPath("$.whatIf.monthlySpending.value").value("-10"))
            .andExpect(jsonPath("$.whatIf.targetYear.kind").value("delta"))
            .andExpect(jsonPath("$.whatIf.targetYear.value").value(-2))
            .andExpect(jsonPath("$.whatIf.lumpSum").value("5000.00"))
            .andExpect(jsonPath("$.whatIf.cashFlowEvents[0].year").value(nextFiveYears))
            .andExpect(jsonPath("$.whatIf.cashFlowEvents[0].kind").value("lump_sum"))
            .andExpect(jsonPath("$.whatIf.cashFlowEvents[0].amount").value("30000.00"))

        assertThat(model.prompts).hasSize(1)
        assertThat(model.prompts.single().user)
            .isEqualTo("Save 250 more, spend 10% less, retire 2 years earlier, put 5k now and 30k windfall in 5 years")
    }

    @Test
    fun `rejects low-confidence or past-year events cleanly`() {
        val (owner, householdId) = householdWithAiOn("fire-q-low")
        model.responder = {
            ScriptedSuggestionModel.answer(
                """{"whatIf":{"lumpSum":1000},"unsupported":[],"confidence":0.2}""",
            )
        }

        mockMvc.perform(
            post("/api/households/$householdId/fire/question")
                .cookie(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"what is the weather","language":"en"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.understood").value(false))
            .andExpect(jsonPath("$.whatIf").doesNotExist())
    }
}
