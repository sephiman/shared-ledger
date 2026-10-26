package com.sephilabs.sharedledger.analytics.projectionmode

import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

class GroupProjectionModeEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val modes: GroupProjectionModeService,
    private val members: HouseholdMemberRepository,
    private val users: UserRepository,
) : AuthHttpTestBase() {

    @Test
    fun `lists every expense group with its mode, envelope by default`() {
        val session = sessionOf(register(newEmail("modes-list")))
        val household = householdOf(session)
        modes.set(household, "groceries", ProjectionMode.PRORATE)

        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(8))
            .andExpect(jsonPath("$[?(@.groupCode == 'groceries')].mode").value("PRORATE"))
            .andExpect(jsonPath("$[?(@.groupCode == 'home')].mode").value("ENVELOPE"))
    }

    @Test
    fun `a plain member can change a group's mode`() {
        val owner = sessionOf(register(newEmail("modes-owner")))
        val household = householdOf(owner)
        val memberEmail = newEmail("modes-member")
        val member = sessionOf(register(memberEmail))
        val memberId = users.findByEmailIgnoreCase(memberEmail)!!.id
        members.save(HouseholdMember(HouseholdMemberId(household, memberId), HouseholdRole.member))

        update(member, household, "outings", """{"mode":"PRORATE"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.groupCode").value("outings"))
            .andExpect(jsonPath("$.mode").value("PRORATE"))

        assertThat(modes.modes(household).of("outings")).isEqualTo(ProjectionMode.PRORATE)
    }

    @Test
    fun `an unknown mode is a 400 naming the field and changes nothing`() {
        val session = sessionOf(register(newEmail("modes-bad-mode")))
        val household = householdOf(session)

        update(session, household, "home", """{"mode":"LINEAR"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.args[0]").value("mode"))
            .andExpect(jsonPath("$.fields.mode").value("Invalid value"))

        assertThat(modes.modes(household).of("home")).isEqualTo(ProjectionMode.ENVELOPE)
    }

    @Test
    fun `a missing or null mode is a 400 saying it is required`() {
        val session = sessionOf(register(newEmail("modes-null")))
        val household = householdOf(session)

        for (body in listOf("""{}""", """{"mode":null}""")) {
            update(session, household, "home", body)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.args[0]").value("mode"))
                .andExpect(jsonPath("$.fields.mode").value("This field is required"))
        }
    }

    @Test
    fun `an unknown group is a 400 naming the group`() {
        val session = sessionOf(register(newEmail("modes-bad-group")))

        update(session, householdOf(session), "income", """{"mode":"PRORATE"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.args[0]").value("groupCode"))
    }

    @Test
    fun `another household's modes can be neither read nor changed`() {
        val victim = householdOf(sessionOf(register(newEmail("modes-victim"))))
        val intruder = sessionOf(register(newEmail("modes-intruder")))

        mockMvc.perform(get(path(victim)).cookie(intruder))
            .andExpect(status().isForbidden)
        update(intruder, victim, "home", """{"mode":"PRORATE"}""")
            .andExpect(status().isForbidden)

        assertThat(modes.modes(victim).of("home")).isEqualTo(ProjectionMode.ENVELOPE)
    }

    @Test
    fun `the month projection reports the days still ahead`() {
        val session = sessionOf(register(newEmail("modes-projection")))
        val household = householdOf(session)
        update(session, household, "groceries", """{"mode":"PRORATE"}""").andExpect(status().isOk)

        mockMvc.perform(get("/api/households/$household/analytics/money-flow/projection").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.daysRemaining").isNumber)
    }

    private fun update(session: Cookie, household: UUID, group: String, body: String): ResultActions =
        mockMvc.perform(put("${path(household)}/$group").with(csrf()).cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content(body))

    private fun path(household: UUID) = "/api/households/$household/projection-modes"

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
}
