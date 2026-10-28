package com.sephilabs.sharedledger.bank.aisuggestion

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** One household switch and one instance switch govern every AI feature alike. */
@ResourceLock("ai-doubles")
class AiFeaturesGateTest @Autowired constructor(
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
    }

    @Test
    fun `with the setting off all three AI features answer 409 with the one shared code`() {
        val owner = sessionOf(register(newEmail("gate-off")))
        val household = householdOf(owner)

        for (request in featureCalls(household, owner)) {
            mockMvc.perform(request)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value(AI_FEATURES_DISABLED))
        }
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `without a provider the setting and all three AI features answer 404, even with the setting on`() {
        val owner = sessionOf(register(newEmail("gate-absent")))
        val household = householdOf(owner)
        mockMvc.perform(
            put("/api/households/$household/ai-suggestions/settings").with(csrf()).cookie(owner)
                .contentType(MediaType.APPLICATION_JSON).content("""{"enabled":true}"""),
        ).andExpect(status().isOk)
        availability.forced = false

        mockMvc.perform(get("/api/households/$household/ai-suggestions/settings").cookie(owner)).andExpect(status().isNotFound)
        for (request in featureCalls(household, owner)) mockMvc.perform(request).andExpect(status().isNotFound)
        assertThat(model.prompts).isEmpty()
    }

    private fun featureCalls(household: UUID, session: Cookie) = listOf(
        post("/api/households/$household/banks/pending/ai-suggestions").with(csrf()).cookie(session),
        post("/api/households/$household/banks/rules/generalisations").with(csrf()).cookie(session),
        post("/api/households/$household/questions").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("""{"question":"How much on restaurants?","language":"en","askedFrom":"explorer"}"""),
    )

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
    )
}
