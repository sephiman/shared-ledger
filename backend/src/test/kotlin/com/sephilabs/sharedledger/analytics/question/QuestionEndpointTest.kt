package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.ChatAnswer
import com.sephilabs.sharedledger.bank.aisuggestion.ScriptedSuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.bank.aisuggestion.TogglableAiAvailability
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@ResourceLock("ai-doubles")
@ExtendWith(OutputCaptureExtension::class)
class QuestionEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val availability: TogglableAiAvailability,
    private val model: ScriptedSuggestionModel,
    private val members: HouseholdMemberRepository,
    private val users: UserRepository,
    private val transactions: TransactionRepository,
) : AuthHttpTestBase() {

    private val today = LocalDate.now()
    private val lastMonth = today.minusMonths(1)

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

    private fun explorerAnswer(code: String = "outings.restaurants", confidence: Double = 0.92) = """
        {"filters":{"direction":"expense","categories":[{"type":"category","code":"$code"}],
         "range":{"from":"${lastMonth.withDayOfMonth(1)}","to":"$today"},"isRefund":false,"priorYear":true},
         "unsupported":[{"kind":"member","label":"member"}],"confidence":$confidence}
    """.trimIndent()

    @Test
    fun `any member's question comes back as Explorer filters, and only the question, date, language and catalogue leave`() {
        val (owner, household) = householdWithAiOn("q-explorer")
        val member = joinAsMember(household, "q-explorer-member")
        seedTransaction(household, owner, "987.65", "Zalacaín anniversary dinner")
        model.responder = { ScriptedSuggestionModel.answer(explorerAnswer()) }

        ask(member, household, "How much on restaurants since last month, by member?", "es")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.understood").value(true))
            .andExpect(jsonPath("$.target").value("explorer"))
            .andExpect(jsonPath("$.reason").doesNotExist())
            .andExpect(jsonPath("$.expressibleIn.length()").value(2))
            .andExpect(jsonPath("$.filters.direction").value("expense"))
            .andExpect(jsonPath("$.filters.scopes[0].type").value("category"))
            .andExpect(jsonPath("$.filters.scopes[0].code").value("outings.restaurants"))
            .andExpect(jsonPath("$.filters.priorYear").value(true))
            .andExpect(jsonPath("$.filters.range.preset").value("custom"))
            .andExpect(jsonPath("$.filters.range.from").value(lastMonth.withDayOfMonth(1).toString()))
            .andExpect(jsonPath("$.filters.range.to").value(today.toString()))
            .andExpect(jsonPath("$.filters.isRefund").value(false))
            .andExpect(jsonPath("$.unsupported[0].kind").value("member"))
            .andExpect(jsonPath("$.unsupported[0].label").value("member"))

        val prompt = model.prompts.single()
        assertThat(prompt.user).isEqualTo("How much on restaurants since last month, by member?")
        assertThat(prompt.system).contains("Today is $today", "Spanish", "outings.restaurants | ")
        assertThat(prompt.system + prompt.user).doesNotContain("987", "Zalacaín", household.toString())
    }

    @Test
    fun `how much income so far lands on the transactions list from either view, Explorer saying why`() {
        val (owner, household) = householdWithAiOn("q-income")
        model.responder = {
            ScriptedSuggestionModel.answer(
                """{"filters":{"direction":"income","categories":[],"range":{"preset":"all"},"isRefund":false},"unsupported":[],"confidence":0.9}""",
            )
        }

        ask(owner, household, "How much income so far?", askedFrom = "explorer")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.target").value("transactions"))
            .andExpect(jsonPath("$.reason").value("income"))
            .andExpect(jsonPath("$.expressibleIn.length()").value(1))
            .andExpect(jsonPath("$.filters.direction").value("income"))
            .andExpect(jsonPath("$.filters.range.preset").value("all"))
        ask(owner, household, "How much income so far?", askedFrom = "transactions")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.target").value("transactions"))
            .andExpect(jsonPath("$.reason").doesNotExist())
    }

    @Test
    fun `an expense question asked from the list stays on the list and says Explorer could show it`() {
        val (owner, household) = householdWithAiOn("q-list")
        model.responder = { ScriptedSuggestionModel.answer(explorerAnswer()) }

        ask(owner, household, "Restaurants since last month", askedFrom = "transactions")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.target").value("transactions"))
            .andExpect(jsonPath("$.expressibleIn[0]").value("explorer"))
            .andExpect(jsonPath("$.expressibleIn[1]").value("transactions"))
    }

    @Test
    fun `several categories go to Explorer's stacked panel, and asked from the list stay on the list offering it`() {
        val (owner, household) = householdWithAiOn("q-stacked")
        model.responder = {
            ScriptedSuggestionModel.answer(
                """{"filters":{"direction":"expense","categories":[{"type":"group","code":"groceries"},{"type":"group","code":"outings"}],
                   "range":{"preset":"ytd"},"isRefund":false},"unsupported":[],"confidence":0.9}""",
            )
        }

        ask(owner, household, "Groceries and outings this year", askedFrom = "explorer")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.target").value("explorer"))
            .andExpect(jsonPath("$.filters.scopes.length()").value(2))
            .andExpect(jsonPath("$.expressibleIn.length()").value(2))
        ask(owner, household, "Groceries and outings this year", askedFrom = "transactions")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.target").value("transactions"))
            .andExpect(jsonPath("$.expressibleIn[0]").value("explorer"))
    }

    @Test
    fun `an answer that does not fit, cannot be read or is unsure is not understood, and nothing comes back to apply`() {
        val (owner, household) = householdWithAiOn("q-not-understood")

        for (content in listOf(explorerAnswer(code = "outings.cinema"), "You spent about 40 euros.", explorerAnswer(confidence = 0.5))) {
            model.responder = { ScriptedSuggestionModel.answer(content) }
            ask(owner, household, "How much on cinema?")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.understood").value(false))
                .andExpect(jsonPath("$.target").doesNotExist())
                .andExpect(jsonPath("$.filters").doesNotExist())
        }
    }

    @Test
    fun `a question asking only for filters that do not exist is understood without a target`() {
        val (owner, household) = householdWithAiOn("q-unsupported")
        model.responder = {
            ScriptedSuggestionModel.answer(
                """{"filters":null,"unsupported":[{"kind":"member","label":"member"},{"kind":"other","label":"city"}],"confidence":0.95}""",
            )
        }

        ask(owner, household, "What did Ana spend in Madrid?")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.understood").value(true))
            .andExpect(jsonPath("$.target").doesNotExist())
            .andExpect(jsonPath("$.unsupported.length()").value(2))
    }

    @Test
    fun `a second question from the same member while the first is processing is refused`() {
        val (owner, household) = householdWithAiOn("q-busy")
        var second: Int? = null
        model.responder = {
            second = ask(owner, household, "And restaurants?").andReturn().response.status
            ScriptedSuggestionModel.answer(explorerAnswer())
        }

        ask(owner, household, "How much on restaurants?").andExpect(status().isOk)

        assertThat(second).isEqualTo(409)
        assertThat(model.prompts).hasSize(1)
    }

    @Test
    fun `a provider failure answers 502 and leaves the member free to ask again`() {
        val (owner, household) = householdWithAiOn("q-provider-down")
        model.responder = { throw SuggestionModelException("HTTP 503: busy", attempts = 2) }

        ask(owner, household, "How much on restaurants?")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("QUESTION_PROVIDER_ERROR"))

        model.responder = { ScriptedSuggestionModel.answer(explorerAnswer()) }
        ask(owner, household, "How much on restaurants?").andExpect(status().isOk)
    }

    @Test
    fun `the question text is never logged, only its length, with the outcome and usage`(output: CapturedOutput) {
        val (owner, household) = householdWithAiOn("q-log")
        val question = "How much on restaurants with my secret-pelican friend?"
        model.responder = { ChatAnswer(explorerAnswer(), 812, 64, reasoningTokens = 0, latencyMs = 321, attempts = 1) }

        ask(owner, household, question).andExpect(status().isOk)
        model.responder = { ScriptedSuggestionModel.answer("nonsense") }
        ask(owner, household, question).andExpect(status().isOk)

        assertThat(output).doesNotContain("secret-pelican")
        val lines = output.lines().filter { it.contains("ai_question ") }
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains(
            "household=$household", "target=explorer", "valid=true", "unsupported=member",
            "question_chars=${question.length}", "input_tokens=812", "output_tokens=64", "latency_ms=321",
        )
        assertThat(lines[1]).contains("valid=false", "reason=unreadable", "question_chars=${question.length}")
    }

    @Test
    fun `a blank or overlong question is a 400, and the provider is not called`() {
        val (owner, household) = householdWithAiOn("q-bad")

        ask(owner, household, "   ").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        ask(owner, household, "x".repeat(QUESTION_MAX_CHARS + 1))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `another household's question box is out of reach`() {
        val (_, victim) = householdWithAiOn("q-victim")
        val intruder = sessionOf(register(newEmail("q-intruder")))

        ask(intruder, victim, "How much on restaurants?").andExpect(status().isForbidden)
        assertThat(model.prompts).isEmpty()
    }

    private fun ask(
        session: Cookie,
        household: UUID,
        question: String,
        language: String = "en",
        askedFrom: String = "explorer",
    ): ResultActions =
        mockMvc.perform(
            post("/api/households/$household/questions").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("question" to question, "language" to language, "askedFrom" to askedFrom))),
        )

    @Test
    fun `a question without saying where it was asked from is a 400 naming the field`() {
        val (owner, household) = householdWithAiOn("q-no-origin")

        mockMvc.perform(
            post("/api/households/$household/questions").with(csrf()).cookie(owner)
                .contentType(MediaType.APPLICATION_JSON).content("""{"question":"How much?","language":"en"}"""),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_PARAMETER")).andExpect(jsonPath("$.args[0]").value("askedFrom"))
    }

    private fun householdWithAiOn(tag: String): Pair<Cookie, UUID> {
        val owner = sessionOf(register(newEmail(tag)))
        val household = householdOf(owner)
        mockMvc.perform(
            put("/api/households/$household/ai-suggestions/settings").with(csrf()).cookie(owner)
                .contentType(MediaType.APPLICATION_JSON).content("""{"enabled":true}"""),
        ).andExpect(status().isOk)
        return owner to household
    }

    private fun joinAsMember(household: UUID, tag: String): Cookie {
        val email = newEmail(tag)
        val session = sessionOf(register(email))
        members.save(HouseholdMember(HouseholdMemberId(household, users.findByEmailIgnoreCase(email)!!.id), HouseholdRole.member))
        return session
    }

    private fun seedTransaction(household: UUID, owner: Cookie, amount: String, description: String) {
        val userId = UUID.fromString(objectMapper.readTree(me(owner).andReturn().response.contentAsString)["id"].asString())
        transactions.save(
            Transaction(
                householdId = household, occurrenceDate = today, direction = Direction.expense,
                categoryCode = "outings.restaurants", amount = BigDecimal(amount), description = description,
                createdByUserId = userId, updatedByUserId = userId,
            ),
        )
    }

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
    )
}
