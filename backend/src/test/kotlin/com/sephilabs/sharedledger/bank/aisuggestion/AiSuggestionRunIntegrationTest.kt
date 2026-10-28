package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.SuggestionSource
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.admin.HouseholdDataWipeService
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

@ResourceLock("ai-doubles")
class AiSuggestionRunIntegrationTest @Autowired constructor(
    private val fixtures: AiSuggestionFixtures,
    private val service: AiSuggestionService,
    private val model: ScriptedSuggestionModel,
    private val pending: PendingMovementRepository,
    private val runs: AiSuggestionRunRepository,
    private val aiSettings: AiSuggestionSettingsRepository,
    private val rules: CategorizationRuleRepository,
    private val wipe: HouseholdDataWipeService,
) : IntegrationTestBase() {

    @BeforeEach
    fun resetModel() = model.reset()

    @Test
    fun `sends only pending items without any suggestion, whatever their origin`() {
        val seeded = fixtures.household()
        val candidate = fixtures.item(seeded, "Instant Gaming")
        fixtures.item(seeded, "Jumbo", suggested = "groceries.groceries", source = SuggestionSource.rule)
        fixtures.item(seeded, "ETOS", suggested = "health.pharmacy", source = SuggestionSource.ai, confidence = "0.9")
        fixtures.item(seeded, "Rejected Shop", status = MovementStatus.rejected)
        fixtures.item(seeded, "Confirmed Shop", status = MovementStatus.confirmed)
        val other = fixtures.household()
        fixtures.item(other, "Other Household Shop")

        val run = service.start(seeded.household.id, seeded.user)

        assertThat(model.prompts.flatMap { ScriptedSuggestionModel.sent(it) }.map { it.counterparty })
            .containsExactly("Instant Gaming")
        assertThat(run.itemsSent).isEqualTo(1)
        assertThat(fixtures.reload(candidate).suggestionSource).isNull()
    }

    @Test
    fun `sends batches of 25, one after another, all in one provider session`() {
        val seeded = fixtures.household()
        repeat(60) { fixtures.item(seeded, "Shop $it") }

        val run = service.start(seeded.household.id, seeded.user)

        assertThat(model.prompts.map { ScriptedSuggestionModel.sent(it).size }).containsExactly(25, 25, 10)
        assertThat(model.prompts.map { it.sessionId }.toSet()).hasSize(1)
        assertThat(model.prompts.map { it.system }.toSet()).hasSize(1)
        assertThat(run.batchesTotal).isEqualTo(3)
        assertThat(run.batchesDone).isEqualTo(3)
        assertThat(run.itemsSent).isEqualTo(60)
        assertThat(run.status).isEqualTo(AiSuggestionRunStatus.completed)
    }

    @Test
    fun `nothing that identifies the household, its accounts or the amounts reaches the provider`() {
        val seeded = fixtures.household()
        val item = fixtures.item(
            seeded,
            "Instant Gaming",
            description = "Naam: Instant Gaming IBAN: NL04ADYB2017400157 Kenmerk: 16-09-2026",
            amount = "47.99",
            bookingDate = LocalDate.of(2026, 9, 16),
        )

        service.start(seeded.household.id, seeded.user)

        val prompt = model.prompts.single()
        val text = prompt.system + prompt.user
        for (secret in listOf(
            seeded.household.id.toString(), seeded.connectionId.toString(), seeded.accountId.toString(),
            item.id.toString(), item.bankMovementId, "47.99", "NL04ADYB2017400157", "4567",
        )) {
            assertThat(text).doesNotContain(secret)
        }
        assertThat(ScriptedSuggestionModel.sent(prompt).single().fields).containsExactlyEntriesOf(
            linkedMapOf(
                "id" to "m1",
                "counterparty" to "Instant Gaming",
                "description" to "Naam: Instant Gaming IBAN: [IBAN] Kenmerk: 16-09-2026",
                "direction" to "expense",
                "bookingMonth" to "2026-09",
            ),
        )
    }

    @Test
    fun `the system prompt carries the household's catalogue and its rules as examples`() {
        val seeded = fixtures.household()
        rules.save(rule(seeded, "Jumbo", "groceries.groceries"))
        fixtures.item(seeded, "Lidl")

        service.start(seeded.household.id, seeded.user)

        val system = model.prompts.single().system
        assertThat(system).contains("groceries.groceries | Groceries | groceries | expense | yes")
        assertThat(system).contains("income.salary | Salary |  | income | no")
        assertThat(system).contains("counterparty | equals | Jumbo | expense | groceries.groceries")
    }

    @Test
    fun `stores a suggestion at or above 0_85 with its origin and confidence and nothing below it`() {
        val seeded = fixtures.household()
        val sure = fixtures.item(seeded, "Albert Heijn 8591")
        val edge = fixtures.item(seeded, "ETOS")
        val unsure = fixtures.item(seeded, "Action 1386")
        model.answerBy {
            when (it) {
                "Albert Heijn 8591" -> "groceries.groceries" to 0.97
                "ETOS" -> "health.pharmacy" to 0.85
                else -> "groceries.groceries" to 0.84
            }
        }

        val run = service.start(seeded.household.id, seeded.user)

        fixtures.reload(sure).let {
            assertThat(it.suggestedCategoryCode).isEqualTo("groceries.groceries")
            assertThat(it.suggestionSource).isEqualTo(SuggestionSource.ai)
            assertThat(it.suggestionConfidence).isEqualByComparingTo("0.97")
            assertThat(it.status).isEqualTo(MovementStatus.pending)
        }
        assertThat(fixtures.reload(edge).suggestionConfidence).isEqualByComparingTo("0.85")
        fixtures.reload(unsure).let {
            assertThat(it.suggestedCategoryCode).isNull()
            assertThat(it.suggestionSource).isNull()
        }
        assertThat(run.suggestionsStored).isEqualTo(2)
        assertThat(run.itemsUnsuggested).isEqualTo(1)
    }

    @Test
    fun `unknown codes, wrong directions and missing entries are counted as unsuggested`() {
        val seeded = fixtures.household()
        val unknown = fixtures.item(seeded, "Unknown Code Shop")
        val mismatch = fixtures.item(seeded, "Mismatch Shop")
        val missing = fixtures.item(seeded, "Forgotten Shop")
        model.answerBy {
            when (it) {
                "Unknown Code Shop" -> "groceries.supermarket" to 0.99
                "Mismatch Shop" -> "income.salary" to 0.99
                else -> null
            }
        }

        val run = service.start(seeded.household.id, seeded.user)

        assertThat(listOf(unknown, mismatch, missing).map { fixtures.reload(it).suggestedCategoryCode }).containsOnlyNulls()
        assertThat(run.itemsUnsuggested).isEqualTo(3)
        assertThat(run.suggestionsStored).isZero()
    }

    @Test
    fun `an unparseable answer leaves the whole batch unsuggested without failing it`() {
        val seeded = fixtures.household()
        repeat(3) { fixtures.item(seeded, "Shop $it") }
        model.responder = { ScriptedSuggestionModel.answer("I'm sorry, I can't categorise these.") }

        val run = service.start(seeded.household.id, seeded.user)

        assertThat(run.itemsUnsuggested).isEqualTo(3)
        assertThat(run.batchesFailed).isZero()
        assertThat(run.status).isEqualTo(AiSuggestionRunStatus.completed)
    }

    @Test
    fun `a failed batch is counted and the other batches are still stored`() {
        val seeded = fixtures.household()
        repeat(30) { fixtures.item(seeded, "Shop $it") }
        var call = 0
        model.responder = { prompt ->
            call++
            if (call == 1) throw SuggestionModelException("HTTP 502: upstream", attempts = 2)
            val entries = ScriptedSuggestionModel.sent(prompt).map {
                """{"id":"${it.id}","categoryCode":"groceries.groceries","confidence":0.9}"""
            }
            ScriptedSuggestionModel.answer(entries.joinToString(",", "[", "]"))
        }

        val run = service.start(seeded.household.id, seeded.user)

        assertThat(run.batchesFailed).isEqualTo(1)
        assertThat(run.batchesDone).isEqualTo(2)
        assertThat(run.suggestionsStored).isEqualTo(5)
        assertThat(run.status).isEqualTo(AiSuggestionRunStatus.completed)
    }

    @Test
    fun `token usage is added up on the run record but not exposed`() {
        val seeded = fixtures.household()
        repeat(30) { fixtures.item(seeded, "Shop $it") }
        model.responder = { ScriptedSuggestionModel.answer("[]", inputTokens = 4000, outputTokens = 1500) }

        val run = service.start(seeded.household.id, seeded.user)

        val stored = runs.findById(run.id).orElseThrow()
        assertThat(stored.inputTokens).isEqualTo(8000)
        assertThat(stored.outputTokens).isEqualTo(3000)
        assertThat(AiSuggestionRunDto::class.java.declaredFields.map { it.name }).noneMatch { it.contains("token", ignoreCase = true) }
    }

    @Test
    fun `an item resolved while its batch was with the model keeps what the member did`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "ETOS")
        model.responder = { prompt ->
            pending.findById(item.id).orElseThrow().let {
                it.status = MovementStatus.rejected
                pending.saveAndFlush(it)
            }
            val id = ScriptedSuggestionModel.sent(prompt).single().id
            ScriptedSuggestionModel.answer("""[{"id":"$id","categoryCode":"health.pharmacy","confidence":0.99}]""")
        }

        val run = service.start(seeded.household.id, seeded.user)

        assertThat(fixtures.reload(item).suggestedCategoryCode).isNull()
        assertThat(run.suggestionsStored).isZero()
    }

    @Test
    fun `items that already have an AI suggestion are not sent again on a later press`() {
        val seeded = fixtures.household()
        fixtures.item(seeded, "ETOS")
        fixtures.item(seeded, "Somebody")
        model.answerBy { if (it == "ETOS") "health.pharmacy" to 0.95 else "personal.other" to 0.4 }
        service.start(seeded.household.id, seeded.user)
        model.prompts.clear()

        service.start(seeded.household.id, seeded.user)

        assertThat(model.prompts.flatMap { ScriptedSuggestionModel.sent(it) }.map { it.counterparty }).containsExactly("Somebody")
    }

    @Test
    fun `a second run while one is in progress for the household is rejected`() {
        val seeded = fixtures.household()
        fixtures.item(seeded, "Shop")
        runs.saveAndFlush(
            AiSuggestionRun(householdId = seeded.household.id, userId = seeded.user.id, promptVersion = "v", model = "m", batchesTotal = 1),
        )

        assertThatThrownBy { service.start(seeded.household.id, seeded.user) }
            .isInstanceOfSatisfying(AppException::class.java) {
                assertThat(it.code).isEqualTo("AI_SUGGESTION_RUN_IN_PROGRESS")
                assertThat(it.httpStatus).isEqualTo(409)
            }
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `a running run in another household does not block this one`() {
        val busy = fixtures.household()
        runs.saveAndFlush(AiSuggestionRun(householdId = busy.household.id, userId = null, promptVersion = "v", model = "m", batchesTotal = 1))
        val seeded = fixtures.household()
        fixtures.item(seeded, "Shop")

        assertThat(service.start(seeded.household.id, seeded.user).status).isEqualTo(AiSuggestionRunStatus.completed)
    }

    @Test
    fun `nothing to send and a disabled household are both rejected before any call`() {
        val empty = fixtures.household()
        assertThatThrownBy { service.start(empty.household.id, empty.user) }
            .isInstanceOfSatisfying(AppException::class.java) { assertThat(it.code).isEqualTo("AI_SUGGESTION_NO_CANDIDATES") }

        val off = fixtures.household(aiEnabled = false)
        fixtures.item(off, "Shop")
        assertThatThrownBy { service.start(off.household.id, off.user) }
            .isInstanceOfSatisfying(AppException::class.java) { assertThat(it.code).isEqualTo("AI_FEATURES_DISABLED") }
        assertThat(model.prompts).isEmpty()
    }

    @Test
    fun `status reports the candidates and the latest run`() {
        val seeded = fixtures.household()
        fixtures.item(seeded, "ETOS")
        fixtures.item(seeded, "Somebody")
        assertThat(service.status(seeded.household.id)).isEqualTo(AiSuggestionStatusDto(candidates = 2, run = null))
        model.answerBy { if (it == "ETOS") "health.pharmacy" to 0.95 else null }

        val run = service.start(seeded.household.id, seeded.user)

        val status = service.status(seeded.household.id)
        assertThat(status.candidates).isEqualTo(1)
        assertThat(status.run).isEqualTo(run)
    }

    @Test
    fun `delete all data removes the AI suggestions, the run records and the setting`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "ETOS")
        model.answerBy { "health.pharmacy" to 0.95 }
        service.start(seeded.household.id, seeded.user)

        wipe.wipe(seeded.household.id, seeded.user.id)

        assertThat(pending.findById(item.id)).isEmpty
        assertThat(runs.findFirstByHouseholdIdOrderByStartedAtDesc(seeded.household.id)).isNull()
        assertThat(aiSettings.findById(seeded.household.id)).isEmpty
    }

    private fun rule(seeded: AiSuggestionFixtures.Seeded, counterparty: String, category: String) = CategorizationRule(
        householdId = seeded.household.id,
        matchField = RuleField.counterparty,
        matchOp = RuleOp.equals,
        matchValue = counterparty,
        categoryCode = category,
        direction = Direction.expense,
    )
}
