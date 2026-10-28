package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionAccount
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@ResourceLock("ai-doubles")
class AiSuggestionEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val availability: TogglableAiAvailability,
    private val model: ScriptedSuggestionModel,
    private val members: HouseholdMemberRepository,
    private val users: UserRepository,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val pending: PendingMovementRepository,
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
    fun `features say AI exists only when a provider is configured, and only to signed-in users`() {
        val session = sessionOf(register(newEmail("ai-features")))

        mockMvc.perform(get("/api/features").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ai").value(true))
        availability.forced = false
        mockMvc.perform(get("/api/features").cookie(session))
            .andExpect(jsonPath("$.ai").value(false))
        mockMvc.perform(get("/api/features")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the public auth features say nothing about AI`() {
        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ai").doesNotExist())
    }

    @Test
    fun `the setting is off by default and names the model and the provider host`() {
        val session = sessionOf(register(newEmail("ai-settings-default")))

        mockMvc.perform(get(settingsPath(householdOf(session))).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.providerHost").value("ai.test"))
    }

    @Test
    fun `an owner turns the setting on and every member reads it`() {
        val owner = sessionOf(register(newEmail("ai-owner")))
        val household = householdOf(owner)
        val member = joinAsMember(household, "ai-member")

        putSettings(owner, household, """{"enabled":true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))

        mockMvc.perform(get(settingsPath(household)).cookie(member))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
    }

    @Test
    fun `a plain member cannot change the setting`() {
        val owner = sessionOf(register(newEmail("ai-owner2")))
        val household = householdOf(owner)
        val member = joinAsMember(household, "ai-member2")

        putSettings(member, household, """{"enabled":true}""")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("NOT_HOUSEHOLD_OWNER"))
    }

    @Test
    fun `a missing or null enabled is a 400 naming the field`() {
        val session = sessionOf(register(newEmail("ai-bad-body")))
        val household = householdOf(session)

        for (body in listOf("""{}""", """{"enabled":null}""")) {
            putSettings(session, household, body)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.args[0]").value("enabled"))
        }
        putSettings(session, household, """{"enabled":"yes"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
    }

    @Test
    fun `any member can run suggestions and poll the result, which carries no token figures`() {
        val owner = sessionOf(register(newEmail("ai-run-owner")))
        val household = householdOf(owner)
        putSettings(owner, household, """{"enabled":true}""").andExpect(status().isOk)
        val member = joinAsMember(household, "ai-run-member")
        seedPending(household, "ETOS")
        model.answerBy { "health.pharmacy" to 0.93 }

        mockMvc.perform(post(runPath(household)).with(csrf()).cookie(member))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.suggestionsStored").value(1))
            .andExpect(jsonPath("$.inputTokens").doesNotExist())

        mockMvc.perform(get(runPath(household)).cookie(member))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.candidates").value(0))
            .andExpect(jsonPath("$.run.batchesDone").value(1))
            .andExpect(jsonPath("$.run.outputTokens").doesNotExist())

        mockMvc.perform(get("/api/households/$household/banks/pending?categorisation=suggested_by_ai").cookie(member))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].suggestionSource").value("ai"))
            .andExpect(jsonPath("$.items[0].suggestionConfidence").value("0.93"))
    }

    @Test
    fun `with the setting off a run is refused`() {
        val owner = sessionOf(register(newEmail("ai-off")))
        val household = householdOf(owner)
        seedPending(household, "ETOS")

        mockMvc.perform(post(runPath(household)).with(csrf()).cookie(owner))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("AI_FEATURES_DISABLED"))
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `without a provider every AI endpoint answers 404 as if it did not exist`() {
        val owner = sessionOf(register(newEmail("ai-absent")))
        val household = householdOf(owner)
        putSettings(owner, household, """{"enabled":true}""").andExpect(status().isOk)
        seedPending(household, "ETOS")
        availability.forced = false

        mockMvc.perform(get(settingsPath(household)).cookie(owner)).andExpect(status().isNotFound)
        putSettings(owner, household, """{"enabled":false}""").andExpect(status().isNotFound)
        mockMvc.perform(get(runPath(household)).cookie(owner)).andExpect(status().isNotFound)
        mockMvc.perform(post(runPath(household)).with(csrf()).cookie(owner)).andExpect(status().isNotFound)
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `another household's AI endpoints are out of reach`() {
        val victim = householdOf(sessionOf(register(newEmail("ai-victim"))))
        val intruder = sessionOf(register(newEmail("ai-intruder")))

        mockMvc.perform(get(settingsPath(victim)).cookie(intruder)).andExpect(status().isForbidden)
        mockMvc.perform(post(runPath(victim)).with(csrf()).cookie(intruder)).andExpect(status().isForbidden)
    }

    private fun putSettings(session: Cookie, household: UUID, body: String): ResultActions =
        mockMvc.perform(
            put(settingsPath(household)).with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )

    private fun joinAsMember(household: UUID, tag: String): Cookie {
        val email = newEmail(tag)
        val session = sessionOf(register(email))
        members.save(HouseholdMember(HouseholdMemberId(household, users.findByEmailIgnoreCase(email)!!.id), HouseholdRole.member))
        return session
    }

    private fun seedPending(household: UUID, counterparty: String) {
        val connection = connections.save(BankConnection(householdId = household, aspspName = "ING", aspspCountry = "NL"))
        val account = accounts.save(BankConnectionAccount(connectionId = connection.id, accountUid = "acc-${System.nanoTime()}"))
        pending.save(
            PendingMovement(
                householdId = household, connectionId = connection.id, accountId = account.id,
                bankMovementId = "bm-${UUID.randomUUID()}", bookingDate = LocalDate.of(2026, 9, 1),
                direction = Direction.expense, amount = BigDecimal("3.50"), counterparty = counterparty,
            ),
        )
    }

    private fun settingsPath(household: UUID) = "/api/households/$household/ai-suggestions/settings"

    private fun runPath(household: UUID) = "/api/households/$household/banks/pending/ai-suggestions"

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
    )
}
