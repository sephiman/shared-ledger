package com.sephilabs.sharedledger.analytics.question

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
import java.util.UUID

@ResourceLock("ai-doubles")
class ProjectionQuestionEndpointTest @Autowired constructor(
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
    fun `returns structured scenario and explain from projection endpoint without sending amounts`() {
        val (owner, householdId) = householdWithAiOn("proj-ask")

        model.responder = {
            ScriptedSuggestionModel.answer(
                """
                {
                  "scenario": {
                    "freeze": [{"type": "group", "code": "outings"}],
                    "adjustments": [],
                    "level": null,
                    "windowMonths": null,
                    "modeOverride": null,
                    "savingsTarget": 500
                  },
                  "explain": {"kind": "outlook", "scope": null},
                  "unsupported": [],
                  "confidence": 0.91
                }
                """.trimIndent(),
            )
        }

        mockMvc.perform(
            post("/api/households/$householdId/questions/projection")
                .cookie(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("question" to "What if we freeze food and save 500?", "language" to "en"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.understood").value(true))
            .andExpect(jsonPath("$.scenario.freeze[0].type").value("group"))
            .andExpect(jsonPath("$.scenario.freeze[0].code").value("outings"))
            .andExpect(jsonPath("$.scenario.savingsTarget").value(500))
            .andExpect(jsonPath("$.explain.kind").value("outlook"))

        assertThat(model.prompts).hasSize(1)
        assertThat(model.prompts.single().user).isEqualTo("What if we freeze food and save 500?")
    }

    @Test
    fun `returns 409 when AI features are disabled for the household`() {
        val owner = sessionOf(register(newEmail("proj-ask-off")))
        val householdId = UUID.fromString(
            objectMapper.readTree(me(owner).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
        )

        mockMvc.perform(
            post("/api/households/$householdId/questions/projection")
                .cookie(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"Why is housing so high?","language":"en"}"""),
        )
            .andExpect(status().isConflict)
    }
}
