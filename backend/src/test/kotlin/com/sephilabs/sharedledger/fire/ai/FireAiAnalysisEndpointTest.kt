package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.bank.aisuggestion.ScriptedSuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.TogglableAiAvailability
import com.sephilabs.sharedledger.fire.FireDefaults
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@ResourceLock("ai-doubles")
@ResourceLock("fire-section-cache")
class FireAiAnalysisEndpointTest @Autowired constructor(
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
    fun `runs grounded FIRE plan review, passes prior evolution context, supports followups and delete`() {
        val (owner, householdId) = householdWithAiOn("fire-ai-review")
        val targetYear = FireDefaults.defaultTargetYear().toInt()

        model.responder = {
            ScriptedSuggestionModel.answer(
                """
                {
                  "summary": "Your plan is in its early build phase.",
                  "evolution": null,
                  "strengths": [
                    {
                      "title": "Consistent baseline",
                      "detail": "Inflation assumption is modeled cleanly.",
                      "citations": [{"path": "headline.expectedInflationPct", "value": 2.0}]
                    }
                  ],
                  "improvements": [
                    {
                      "title": "Track spending in the ledger",
                      "detail": "Lookback window has limited data.",
                      "severity": "warning",
                      "citations": [{"path": "assumptionsVsReality.spending.monthsAvailable", "value": 0}]
                    }
                  ],
                  "suggestedWhatIfs": [
                    {
                      "title": "Increase monthly saving",
                      "rationale": "Adding contributions accelerates target year.",
                      "expectedImpact": "Higher probability of reaching target",
                      "preview": {"monthlyContribution": 1200, "targetYear": 1800, "returnShiftPct": 1.0},
                      "citations": [{"path": "headline.targetYear", "value": $targetYear}]
                    }
                  ],
                  "observations": [
                    {
                      "claim": "Current horizon is set to $targetYear.",
                      "confidence": "high",
                      "citations": [
                        {"path": "scope.targetYear", "value": $targetYear},
                        {"path": "scope.targetYear", "value": 9999}
                      ]
                    }
                  ],
                  "dataGaps": [
                    {
                      "topic": "Spending history",
                      "reason": "No months of spending recorded yet.",
                      "citations": [{"path": "assumptionsVsReality.spending.monthsAvailable", "value": 0}]
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val createdJson = mockMvc.perform(
            post("/api/households/$householdId/fire/ai-analysis")
                .cookie(owner)
                .with(csrf()),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.summary").value("Your plan is in its early build phase."))
            .andExpect(jsonPath("$.strengths[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$.observations[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$.observations[0].citations[1].grounded").value(false))
            .andExpect(jsonPath("$.citationsUngrounded").value(1))
            // Invalid targetYear (1800) is stripped while valid preview fields stay.
            .andExpect(jsonPath("$.suggestedWhatIfs[0].preview.monthlyContribution").value("1200.00"))
            .andExpect(jsonPath("$.suggestedWhatIfs[0].preview.targetYear").doesNotExist())
            .andExpect(jsonPath("$.suggestedWhatIfs[0].preview.returnShiftPct").value("1.0"))
            .andReturn().response.contentAsString

        val runId = objectMapper.readTree(createdJson)["id"].asString()

        // Second run should include prior context for evolution.
        model.responder = {
            ScriptedSuggestionModel.answer(
                """
                {
                  "summary": "Second review completed.",
                  "evolution": "Horizon remains 2050.",
                  "strengths": [],
                  "improvements": [],
                  "suggestedWhatIfs": [],
                  "observations": [
                    {"claim": "Still targeting $targetYear.", "confidence": "high", "citations": [{"path": "scope.targetYear", "value": $targetYear}]}
                  ],
                  "dataGaps": []
                }
                """.trimIndent(),
            )
        }
        mockMvc.perform(
            post("/api/households/$householdId/fire/ai-analysis")
                .cookie(owner)
                .with(csrf()),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.evolution").value("Horizon remains 2050."))

        assertThat(model.prompts).hasSize(2)
        assertThat(model.prompts[1].user).contains("Prior FIRE review summary for context:")
        assertThat(model.prompts[1].user).contains("Your plan is in its early build phase.")

        // Follow-up Q&A on the first run.
        model.responder = {
            ScriptedSuggestionModel.answer(
                """
                {
                  "answer": "Your inflation assumption is 2.0%.",
                  "citations": [{"path": "headline.expectedInflationPct", "value": 2.0}]
                }
                """.trimIndent(),
            )
        }
        mockMvc.perform(
            post("/api/households/$householdId/fire/ai-analysis/$runId/followups")
                .cookie(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"What inflation rate is assumed?"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answer").value("Your inflation assumption is 2.0%."))
            .andExpect(jsonPath("$.citationsTotal").value(1))
            .andExpect(jsonPath("$.citationsUngrounded").value(0))

        mockMvc.perform(get("/api/households/$householdId/fire/ai-analysis").cookie(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].followups.length()").value(1))

        mockMvc.perform(
            delete("/api/households/$householdId/fire/ai-analysis/$runId")
                .cookie(owner)
                .with(csrf()),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/households/$householdId/fire/ai-analysis").cookie(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `marks run failed when provider returns unreadable output`() {
        val (owner, householdId) = householdWithAiOn("fire-ai-bad")
        model.responder = { ScriptedSuggestionModel.answer("not json at all") }

        mockMvc.perform(
            post("/api/households/$householdId/fire/ai-analysis")
                .cookie(owner)
                .with(csrf()),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.errorCode").value("UNREADABLE_ANSWER"))
    }
}
