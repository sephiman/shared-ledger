package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.RuleSource
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettings
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettingsRepository
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.ScriptedSuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.ScriptedSuggestionModel.Companion.answer
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.bank.aisuggestion.TogglableAiAvailability
import com.sephilabs.sharedledger.bank.rulegeneralisation.RuleGeneralisationFixtures.Companion.GROCERIES
import com.sephilabs.sharedledger.bank.rulegeneralisation.RuleGeneralisationFixtures.Companion.REAL_RULES
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@ResourceLock("ai-doubles")
class RuleGeneralisationEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val availability: TogglableAiAvailability,
    private val model: ScriptedSuggestionModel,
    private val members: HouseholdMemberRepository,
    private val users: UserRepository,
    private val rules: CategorizationRuleRepository,
    private val aiSettings: AiSuggestionSettingsRepository,
    private val runs: RuleGeneralisationRunRepository,
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

    // --- Gating -----------------------------------------------------------------------------------

    @Test
    fun `without a provider every generalisation endpoint answers 404 as if it did not exist`() {
        val (session, household) = household("rg-absent")
        availability.forced = false

        mockMvc.perform(get(path(household)).cookie(session)).andExpect(status().isNotFound)
        mockMvc.perform(post(path(household)).with(csrf()).cookie(session)).andExpect(status().isNotFound)
        postJson(session, "${path(household)}/validate", request("Vomar", ids(household, "Vomar Filmwijk", "Vomar Almere Stad")))
            .andExpect(status().isNotFound)
        postJson(session, "${path(household)}/accept", request("Vomar", ids(household, "Vomar Filmwijk", "Vomar Almere Stad")))
            .andExpect(status().isNotFound)
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `with the household setting off every generalisation endpoint is refused`() {
        val (session, household) = household("rg-off", aiEnabled = false)
        val body = request("Vomar", ids(household, "Vomar Filmwijk", "Vomar Almere Stad"))

        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AI_SUGGESTIONS_DISABLED"))
        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AI_SUGGESTIONS_DISABLED"))
        postJson(session, "${path(household)}/validate", body)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AI_SUGGESTIONS_DISABLED"))
        postJson(session, "${path(household)}/accept", body)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("AI_SUGGESTIONS_DISABLED"))
        assertThat(model.prompts).isEmpty()
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household)).hasSize(REAL_RULES.size)
    }

    @Test
    fun `a household with fewer counterparty or description rules than the minimum cannot run`() {
        val (session, household) = household("rg-few", seed = false)
        rule(household, "Shop", GROCERIES, op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        rule(household, "0..5", GROCERIES, field = RuleField.amount, op = RuleOp.range)

        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eligibleRules").value(1))
            .andExpect(jsonPath("$.minRules").value(RULE_GENERALISATION_MIN_RULES))
            .andExpect(jsonPath("$.run").doesNotExist())
        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RULE_GENERALISATION_TOO_FEW_RULES"))
        assertThat(model.prompts).isEmpty()

        rule(household, "Kiosk", GROCERIES, field = RuleField.description, source = RuleSource.manual, priority = 100)
        mockMvc.perform(get(path(household)).cookie(session)).andExpect(jsonPath("$.eligibleRules").value(2))
        mockMvc.perform(post(path(household)).with(csrf()).cookie(session)).andExpect(status().isAccepted)
    }

    @Test
    fun `a run already in progress refuses a second press`() {
        val (session, household) = household("rg-busy")
        runs.saveAndFlush(
            RuleGeneralisationRun(householdId = household, userId = null, promptVersion = "v", model = "m", rulesTotal = 1, rulesSent = 1),
        )

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RULE_GENERALISATION_RUN_IN_PROGRESS"))
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `another household's generalisations are out of reach`() {
        val (_, victim) = household("rg-victim")
        val (intruder, _) = household("rg-intruder")

        mockMvc.perform(get(path(victim)).cookie(intruder)).andExpect(status().isForbidden)
        mockMvc.perform(post(path(victim)).with(csrf()).cookie(intruder)).andExpect(status().isForbidden)
        postJson(intruder, "${path(victim)}/accept", request("Vomar", ids(victim, "Vomar Filmwijk", "Vomar Almere Stad")))
            .andExpect(status().isForbidden)
    }

    // --- What is sent -----------------------------------------------------------------------------

    @Test
    fun `only rule values, fields, directions and categories leave, under per-run ids, with IBANs stripped`() {
        val (session, household) = household("rg-sent")
        rule(household, "Loonzorg NL91ABNA0417164300", "income.salary", Direction.income)
        rule(household, "0..5", "personal.other", field = RuleField.amount, op = RuleOp.range)

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session)).andExpect(status().isAccepted)

        val prompt = model.prompts.single()
        val sent = sentRules(prompt)
        assertThat(sent).hasSize(REAL_RULES.size + 1)
        assertThat(sent.map { it.key }).allMatch { it.matches(Regex("r\\d+")) }
        assertThat(sent.values.map { it.properties().map { p -> p.key }.toSet() }.distinct())
            .containsExactly(setOf("id", "field", "match", "value", "direction", "category"))
        assertThat(sent.values.map { it["field"].asString() }).doesNotContain("amount")
        assertThat(prompt.user).doesNotContain("NL91ABNA0417164300").contains("Loonzorg [IBAN]")
        val ruleIds = rules.findAllByHouseholdIdOrderByPriorityAsc(household).map { it.id.toString() }
        assertThat(ruleIds).allMatch { it !in prompt.user && it !in prompt.system }
        assertThat(prompt.user + prompt.system).doesNotContain(household.toString())
        assertThat(prompt.system).contains("groceries.groceries | ")
    }

    @Test
    fun `above the cap only the most recent rules are sent, and the run says so`() {
        val (session, household) = household("rg-cap", seed = false)
        val base = Instant.parse("2026-01-01T00:00:00Z")
        repeat(RULE_GENERALISATION_MAX_RULES + 5) { i ->
            rules.save(
                CategorizationRule(
                    householdId = household, matchField = RuleField.counterparty, matchOp = RuleOp.equals,
                    matchValue = "Shop $i", categoryCode = GROCERIES, direction = Direction.expense, priority = 200,
                    source = RuleSource.learned,
                ).apply { createdAt = base.plusSeconds(i.toLong()) },
            )
        }

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.rulesTotal").value(RULE_GENERALISATION_MAX_RULES + 5))
            .andExpect(jsonPath("$.rulesSent").value(RULE_GENERALISATION_MAX_RULES))

        val values = sentRules(model.prompts.single()).values.map { it["value"].asString() }
        assertThat(values).hasSize(RULE_GENERALISATION_MAX_RULES).doesNotContain("Shop 0", "Shop 4").contains("Shop 5", "Shop 304")
    }

    // --- A run and its proposals ------------------------------------------------------------------

    @Test
    fun `any member runs it and sees valid, colliding and discarded proposals, without token figures`() {
        val (owner, household) = household("rg-run")
        val member = joinAsMember(household, "rg-run-member")
        answerWith(
            proposal("Albert Heijn", listOf("Albert Heijn 8591", "Albert Heijn 1077 Amsterdam"), 0.95),
            proposal("Lidl", listOf("Lidl 737 Almere", "LIDL 1203 Almere Haven"), 0.93),
            proposal("Almere", listOf("Lidl 737 Almere", "Vomar Filmwijk"), 0.9),
            proposal("Vomar", listOf("Vomar Filmwijk", "Vomar Almere Stad"), 0.5),
            proposal("NS", listOf("NS Groep IZ NS Reizigers", "NS-Almere C."), 0.9, "transport.public"),
            proposal("Vattenfall", listOf("Vattenfall Energie"), 0.9, "home.utilities"),
            """{"pattern":"Loonzorg","field":"counterparty","direction":"income"}""",
        )

        mockMvc.perform(post(path(household)).with(csrf()).cookie(member))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.proposalsReturned").value(7))
            .andExpect(jsonPath("$.proposalsShown").value(2))
            .andExpect(jsonPath("$.proposalsDiscarded").value(5))
            .andExpect(jsonPath("$.inputTokens").doesNotExist())

        mockMvc.perform(get(path(household)).cookie(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.run.outputTokens").doesNotExist())
            .andExpect(jsonPath("$.proposalsExpired").value(false))
            .andExpect(jsonPath("$.proposals.length()").value(2))
            .andExpect(jsonPath("$.proposals[0].pattern").value("Albert Heijn"))
            .andExpect(jsonPath("$.proposals[0].check.status").value("ok"))
            .andExpect(jsonPath("$.proposals[0].absorbed[0].matchValue").value("Albert Heijn 8591"))
            .andExpect(jsonPath("$.proposals[1].pattern").value("Lidl"))
            .andExpect(jsonPath("$.proposals[1].check.status").value("conflict"))
            .andExpect(jsonPath("$.proposals[1].check.collisionCount").value(1))
            .andExpect(jsonPath("$.proposals[1].check.collisionExamples[0]").value("Lidl Reisen"))
    }

    @Test
    fun `a provider failure or an unreadable answer fails the run and shows nothing`() {
        val (session, household) = household("rg-fail")
        model.responder = { throw SuggestionModelException("http_503", attempts = 2) }

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("failed"))
        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(jsonPath("$.run.status").value("failed"))
            .andExpect(jsonPath("$.proposals").doesNotExist())

        model.responder = { answer("Sorry, I cannot help with that.") }
        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("failed"))
    }

    @Test
    fun `validate judges an edited pattern with the server's own rules`() {
        val (session, household) = household("rg-validate")
        val lidl = ids(household, "Lidl 737 Almere", "LIDL 1203 Almere Haven")

        postJson(session, "${path(household)}/validate", request("lidl 7", lidl))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("invalid"))
            .andExpect(jsonPath("$.problem").value("not_covering"))
        postJson(session, "${path(household)}/validate", request("Lidl", lidl))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("conflict"))
            .andExpect(jsonPath("$.collisionExamples[0]").value("Lidl Reisen"))
        postJson(session, "${path(household)}/validate", request("Almere", lidl))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("conflict"))
            .andExpect(jsonPath("$.collisionExamples[0]").value("NS-Almere C."))
        postJson(session, "${path(household)}/validate", request("vomar", ids(household, "Vomar Filmwijk", "Vomar Almere Stad")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.collisionCount").value(0))
    }

    // --- Accept -----------------------------------------------------------------------------------

    @Test
    fun `accepting writes one contains rule after every rule of its direction and removes the absorbed ones`() {
        val (session, household) = household("rg-accept")
        answerWith(
            proposal("Vomar", listOf("Vomar Filmwijk", "Vomar Almere Stad"), 0.95),
            proposal("Almere", listOf("Vomar Almere Stad", "Lidl 737 Almere"), 0.9),
        )
        rule(household, "Etos", "health.pharmacy", op = RuleOp.contains, priority = 450, source = RuleSource.manual)
        val run = runId(mockMvc.perform(post(path(household)).with(csrf()).cookie(session)).andReturn().response.contentAsString)
        val absorbed = ids(household, "Vomar Filmwijk", "Vomar Almere Stad")

        postJson(session, "${path(household)}/accept", request("Vomar", absorbed, runId = run))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(2))
            .andExpect(jsonPath("$.rule.matchField").value("counterparty"))
            .andExpect(jsonPath("$.rule.matchOp").value("contains"))
            .andExpect(jsonPath("$.rule.matchValue").value("Vomar"))
            .andExpect(jsonPath("$.rule.source").value("manual"))
            .andExpect(jsonPath("$.rule.priority").value(451))

        val after = rules.findAllByHouseholdIdOrderByPriorityAsc(household)
        assertThat(after.map { it.id }).doesNotContainAnyElementsOf(absorbed)
        assertThat(after.last().matchValue).isEqualTo("Vomar")
        assertThat(runs.findById(run).orElseThrow().proposalsAccepted).isEqualTo(1)
        // The other proposal shared a rule with the accepted one: re-validated, it is now stale.
        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(jsonPath("$.proposals[1].check.status").value("invalid"))
            .andExpect(jsonPath("$.proposals[1].check.problem").value("unknown_rule"))
    }

    @Test
    fun `accept refuses a collision, a precedence conflict and stale ids with 409, writing nothing`() {
        val (session, household) = household("rg-accept-409")
        rule(household, "almere stad", "shopping.other", op = RuleOp.contains, priority = 250, source = RuleSource.manual)
        val before = ruleIds(household)

        postJson(session, "${path(household)}/accept", request("Lidl", ids(household, "Lidl 737 Almere", "LIDL 1203 Almere Haven")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RULE_GENERALISATION_COLLISION"))
            .andExpect(jsonPath("$.args[0]").value("1"))
        postJson(session, "${path(household)}/accept", request("Vomar", ids(household, "Vomar Filmwijk", "Vomar Almere Stad")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RULE_GENERALISATION_PRECEDENCE"))
        postJson(session, "${path(household)}/accept", request("Vattenfall", ids(household, "Vattenfall Energie") + UUID.randomUUID(), "home.utilities"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RULE_GENERALISATION_STALE"))
        assertThat(ruleIds(household)).isEqualTo(before)
    }

    @Test
    fun `accept refuses a proposal that breaks a rule of its own with 400 naming the field`() {
        val (session, household) = household("rg-accept-400")
        val vomar = ids(household, "Vomar Filmwijk", "Vomar Almere Stad")

        postJson(session, "${path(household)}/accept", request("Vo", vomar))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields.pattern").value("The pattern must be at least 3 characters long"))
        postJson(session, "${path(household)}/accept", request("Vomar", vomar, field = RuleField.amount))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.field").exists())
        postJson(session, "${path(household)}/accept", request("Vomar", vomar.take(1)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.absorbedRuleIds").exists())
        val unknown = listOf(rule(household, "Kruidvat 1", "groceries.nope").id, rule(household, "Kruidvat 2", "groceries.nope").id)
        postJson(session, "${path(household)}/accept", request("Kruidvat", unknown, "groceries.nope"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.categoryCode").exists())
        postJson(session, "${path(household)}/accept", """{"field":"counterparty","direction":"expense","categoryCode":"groceries.groceries","absorbedRuleIds":[]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.args[0]").value("pattern"))
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household)).hasSize(REAL_RULES.size + 2)
    }

    @Test
    fun `description rules, manual and contains included, become one description rule`() {
        val (session, household) = household("rg-description")
        val a = rule(household, "Vodafone Servicios", "home.utilities", field = RuleField.description, op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        val b = rule(household, "/TXT/D|RECIB /Vodafone Ono", "home.utilities", field = RuleField.description, priority = 100, source = RuleSource.manual)
        model.responder = { prompt ->
            val ids = sentRules(prompt).values.filter { it["value"].asString().contains("Vodafone") }.map { "\"${it["id"].asString()}\"" }
            answer("""[{"pattern":"Vodafone","field":"description","direction":"expense","categoryCode":"home.utilities","absorbs":$ids,"confidence":0.94}]""")
        }

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session)).andExpect(jsonPath("$.proposalsShown").value(1))
        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(jsonPath("$.proposals[0].field").value("description"))
            .andExpect(jsonPath("$.proposals[0].check.status").value("ok"))

        postJson(session, "${path(household)}/accept", request("Vodafone", listOf(a.id, b.id), "home.utilities", field = RuleField.description))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rule.matchField").value("description"))
            .andExpect(jsonPath("$.rule.matchOp").value("contains"))
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(household).map { it.id }).doesNotContain(a.id, b.id)
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun path(household: UUID) = "/api/households/$household/banks/rules/generalisations"

    private fun household(tag: String, aiEnabled: Boolean = true, seed: Boolean = true): Pair<Cookie, UUID> {
        val session = sessionOf(register(newEmail(tag)))
        val household = UUID.fromString(
            objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
        )
        if (aiEnabled) aiSettings.save(AiSuggestionSettings(householdId = household, enabled = true))
        if (seed) REAL_RULES.forEach { (value, category, direction) -> rule(household, value, category, direction) }
        return session to household
    }

    private fun rule(
        household: UUID,
        value: String,
        category: String,
        direction: Direction = Direction.expense,
        field: RuleField = RuleField.counterparty,
        op: RuleOp = RuleOp.equals,
        priority: Int = 200,
        source: RuleSource = RuleSource.learned,
    ) = rules.save(
        CategorizationRule(
            householdId = household, matchField = field, matchOp = op, matchValue = value, categoryCode = category,
            direction = direction, priority = priority, source = source,
        ),
    )

    private fun ids(household: UUID, vararg values: String): List<UUID> {
        val byValue = rules.findAllByHouseholdIdOrderByPriorityAsc(household).associateBy { it.matchValue }
        return values.map { byValue.getValue(it).id }
    }

    private fun ruleIds(household: UUID) = rules.findAllByHouseholdIdOrderByPriorityAsc(household).map { it.id }.toSet()

    private fun request(
        pattern: String,
        ids: List<UUID>,
        category: String = GROCERIES,
        direction: Direction = Direction.expense,
        runId: UUID? = null,
        field: RuleField = RuleField.counterparty,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "pattern" to pattern, "field" to field, "direction" to direction, "categoryCode" to category,
            "absorbedRuleIds" to ids, "runId" to runId,
        ),
    )

    private fun postJson(session: Cookie, url: String, body: String): ResultActions =
        mockMvc.perform(post(url).with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun joinAsMember(household: UUID, tag: String): Cookie {
        val email = newEmail(tag)
        val session = sessionOf(register(email))
        members.save(HouseholdMember(HouseholdMemberId(household, users.findByEmailIgnoreCase(email)!!.id), HouseholdRole.member))
        return session
    }

    private fun runId(body: String): UUID = UUID.fromString(objectMapper.readTree(body)["id"].asString())

    /** value -> the JSON the provider received for that rule. */
    private fun sentRules(prompt: ChatPrompt): Map<String, JsonNode> =
        prompt.user.lines().drop(1).filter { it.isNotBlank() }.map { objectMapper.readTree(it) }.associateBy { it["id"].asString() }

    /** A proposal naming its rules by value; the responder swaps in the ids they were sent under. */
    private fun proposal(pattern: String, values: List<String>, confidence: Double, category: String = GROCERIES) =
        """{"pattern":"$pattern","field":"counterparty","direction":"expense","categoryCode":"$category","absorbs":${values.joinToString(",", "[", "]") { "\"@$it\"" }},"confidence":$confidence}"""

    private fun answerWith(vararg proposals: String) {
        model.responder = { prompt ->
            val idByValue = sentRules(prompt).values.associate { it["value"].asString() to it["id"].asString() }
            var content = proposals.joinToString(",", "[", "]")
            for ((value, id) in idByValue) content = content.replace("\"@$value\"", "\"$id\"")
            answer(content)
        }
    }
}
