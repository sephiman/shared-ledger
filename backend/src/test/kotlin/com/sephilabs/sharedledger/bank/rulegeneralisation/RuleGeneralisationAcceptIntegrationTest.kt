package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.CategorizationService
import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.bank.PendingMovementService
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.RuleSource
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionFixtures
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus
import com.sephilabs.sharedledger.bank.rulegeneralisation.RuleGeneralisationFixtures.Companion.GROCERIES
import com.sephilabs.sharedledger.bank.rulegeneralisation.RuleGeneralisationFixtures.Companion.REAL_RULES
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.admin.HouseholdDataWipeService
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Accepting is a rule rewrite: it must leave the engine answering exactly as before for everything it absorbed,
 *  and happen entirely or not at all. */
@ResourceLock("ai-doubles")
class RuleGeneralisationAcceptIntegrationTest @Autowired constructor(
    private val fixtures: RuleGeneralisationFixtures,
    private val ai: AiSuggestionFixtures,
    private val service: RuleGeneralisationService,
    private val categorization: CategorizationService,
    private val rules: CategorizationRuleRepository,
    private val pending: PendingMovementRepository,
    private val pendingService: PendingMovementService,
    private val jdbc: JdbcTemplate,
    private val runs: RuleGeneralisationRunRepository,
    private val wipe: HouseholdDataWipeService,
) : IntegrationTestBase() {

    private data class Accepted(val pattern: String, val values: List<String>, val category: String, val direction: Direction = Direction.expense)

    private val generalisations = listOf(
        Accepted("Albert Heijn", listOf("Albert Heijn 8591", "Albert Heijn 1077 Amsterdam"), GROCERIES),
        Accepted("AH to go", listOf("BCK*AH to go 5868 Adam", "AH to go 5501 Utrecht"), GROCERIES),
        Accepted("Vomar", listOf("Vomar Filmwijk", "Vomar Almere Stad"), GROCERIES),
        Accepted("NS Reizigers", listOf("NS Groep IZ NS Reizigers", "NS Reizigers B.V."), "transport.public"),
        Accepted("Vattenfall", listOf("Vattenfall Klantenservice N.V.", "Vattenfall Energie"), "home.utilities"),
        Accepted("Loonzorg", listOf("Loonzorg B.V.", "Loonzorg Salarisadministratie"), "income.salary", Direction.income),
    )

    private companion object {
        val NEWLY_COVERED = mapOf(("Albert Heijn Online" to Direction.expense) to GROCERIES)
    }

    private data class Probe(val counterparty: String, val direction: Direction, val description: String = "Card payment")

    @Test
    fun `for every absorbed counterparty, and every other one, the engine answers exactly as before`() {
        val h = realHousehold()
        val probes = probes()
        val before = probes.associateWith { suggestion(h, it) }

        acceptAll(h)

        val after = probes.associateWith { suggestion(h, it) }
        val absorbedValues = generalisations.flatMap { it.values }.toSet()
        assertThat(probes.filter { it.counterparty in absorbedValues && before[it] != null })
            .hasSize(absorbedValues.size + 3)
        assertUnchangedButNewlyCovered(before, after) { it.counterparty to it.direction }
        // What the generalisation is for: a branch no rule knew is now covered, so it never goes to the model.
        assertThat(suggestion(h, Probe("Albert Heijn 2210 Almere", Direction.expense))).isEqualTo(GROCERIES)
    }

    @Test
    fun `apply rules on the inbox suggests the same categories before and after accepting`() {
        val h = realHousehold()
        val items = probes().map { p ->
            ai.item(h.seeded, p.counterparty, direction = p.direction, description = p.description)
        }
        pendingService.applyRulesToPending(h.id)
        val before = items.associate { it.id to pending.findById(it.id).orElseThrow().suggestedCategoryCode }
        clearSuggestions(items)

        acceptAll(h)
        pendingService.applyRulesToPending(h.id)

        val after = items.associate { it.id to pending.findById(it.id).orElseThrow().suggestedCategoryCode }
        val byId = items.associateBy { it.id }
        assertUnchangedButNewlyCovered(before, after) { byId.getValue(it).let { m -> m.counterparty!! to m.direction } }
        assertThat(before.values.filterNotNull()).hasSizeGreaterThan(15)
    }

    @Test
    fun `the new rule goes after every rule of its direction, even manual ones set further back`() {
        val h = realHousehold()
        fixtures.rule(h, "Kiosk", "shopping.other", priority = 900, source = RuleSource.manual, op = RuleOp.contains)
        fixtures.rule(h, "Uber", "income.other", Direction.income, priority = 5000, source = RuleSource.manual, op = RuleOp.contains)

        val accepted = accept(h, generalisations.first { it.pattern == "Vomar" })

        assertThat(accepted.rule.priority).isEqualTo(901)
        assertThat(accepted.rule.source).isEqualTo(RuleSource.manual)
        assertThat(accepted.rule.matchOp).isEqualTo(RuleOp.contains)
    }

    @Test
    fun `a failure while removing the absorbed rules leaves the new rule unwritten and the old ones in place`() {
        val h = fixtures.household()
        installFailOnDeleteTrigger()
        val a = fixtures.rule(h, "Jumbo 4410 fail-on-delete", GROCERIES)
        val b = fixtures.rule(h, "Jumbo 1201 fail-on-delete", GROCERIES)
        val before = rules.findAllByHouseholdIdOrderByPriorityAsc(h.id).map { it.id to it.matchValue }

        assertThatThrownBy { service.accept(h.id, request("Jumbo", listOf(a.id, b.id)), h.seeded.user) }
            .hasStackTraceContaining("generalisation test: refusing delete")

        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(h.id).map { it.id to it.matchValue }).isEqualTo(before)
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(h.id)).noneMatch { it.matchOp == RuleOp.contains }
    }

    @Test
    fun `two members accepting proposals over the same rules - one wins, the other gets a stale 409`() {
        val h = fixtures.household()
        val ids = h.ids("Vomar Filmwijk", "Vomar Almere Stad")
        val outcomes = race(
            { service.accept(h.id, request("Vomar", ids), h.seeded.user) },
            { service.accept(h.id, request("vomar ", ids), h.seeded.user) },
        )

        assertThat(outcomes.count { it == "ok" }).isEqualTo(1)
        assertThat(outcomes.count { it == "RULE_GENERALISATION_STALE" }).isEqualTo(1)
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(h.id).count { it.matchOp == RuleOp.contains }).isEqualTo(1)
    }

    @Test
    fun `two accepts at once over different rules of one direction never share a priority`() {
        val h = fixtures.household()
        val outcomes = race(
            { service.accept(h.id, request("Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad")), h.seeded.user) },
            {
                service.accept(
                    h.id, request("Vattenfall", h.ids("Vattenfall Klantenservice N.V.", "Vattenfall Energie"), "home.utilities"),
                    h.seeded.user,
                )
            },
        )

        assertThat(outcomes).containsOnly("ok")
        val generalised = rules.findAllByHouseholdIdOrderByPriorityAsc(h.id).filter { it.matchOp == RuleOp.contains }
        assertThat(generalised.map { it.priority }).containsExactlyInAnyOrder(201, 202)
    }

    @Test
    fun `delete all data removes the household's run records`() {
        val h = fixtures.household()
        runs.saveAndFlush(
            RuleGeneralisationRun(householdId = h.id, userId = h.seeded.user.id, promptVersion = "v", model = "m", rulesTotal = 2, rulesSent = 2)
                .apply { status = AiSuggestionRunStatus.completed },
        )

        wipe.wipe(h.id, h.seeded.user.id)

        assertThat(runs.findFirstByHouseholdIdOrderByStartedAtDesc(h.id)).isNull()
    }

    // --- Helpers ----------------------------------------------------------------------------------

    /** Every probe a rule covered before keeps its exact answer; a probe nothing covered may only gain the
     *  generalised category, and only the new branches listed here do. */
    private fun <K> assertUnchangedButNewlyCovered(
        before: Map<K, String?>,
        after: Map<K, String?>,
        key: (K) -> Pair<String, Direction>,
    ) {
        for ((k, was) in before) {
            if (was != null) {
                assertThat(after[k]).`as`("covered before: %s", key(k)).isEqualTo(was)
            } else {
                val expected = NEWLY_COVERED[key(k)]
                assertThat(after[k]).`as`("uncovered before: %s", key(k)).isEqualTo(expected)
            }
        }
    }

    /** The real-shaped rules plus what a household also has: manual rules ahead of the learned ones, and a
     *  description rule set between them, which would lose its movements if the new rule went in too early. */
    private fun realHousehold(): RuleGeneralisationFixtures.Household {
        val h = fixtures.household()
        fixtures.rule(h, "Etos", "health.pharmacy", op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        fixtures.rule(h, "Huur", "home.rent", field = RuleField.description, op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        fixtures.rule(h, "Cadeaukaart", "shopping.gifts", field = RuleField.description, op = RuleOp.contains, priority = 150, source = RuleSource.manual)
        return h
    }

    private fun probes(): List<Probe> {
        val values = REAL_RULES.map { it.first } + listOf("Etos Almere", "Albert Heijn Online", "Lidl 9999", "Jumbo")
        return values.flatMap { v -> Direction.entries.map { d -> Probe(v, d) } } +
            listOf(
                Probe("Albert Heijn 8591", Direction.expense, "Cadeaukaart 25"),
                Probe("Vomar Filmwijk", Direction.expense, "Huur parkeerplaats"),
                Probe("Vattenfall Energie", Direction.expense, "Cadeaukaart"),
            )
    }

    private fun suggestion(h: RuleGeneralisationFixtures.Household, probe: Probe): String? {
        val movement = PendingMovement(
            householdId = h.id, connectionId = h.seeded.connectionId, accountId = h.seeded.accountId,
            bankMovementId = "probe", bookingDate = LocalDate.of(2026, 10, 1), direction = probe.direction,
            amount = BigDecimal("12.34"), counterparty = probe.counterparty, description = probe.description,
        )
        return categorization.matchRule(categorization.orderedRules(h.id), movement)?.categoryCode
    }

    private fun acceptAll(h: RuleGeneralisationFixtures.Household) = generalisations.forEach { accept(h, it) }

    private fun accept(h: RuleGeneralisationFixtures.Household, g: Accepted) =
        service.accept(h.id, request(g.pattern, h.ids(*g.values.toTypedArray()), g.category, g.direction), h.seeded.user)

    private fun request(pattern: String, ids: List<UUID>, category: String = GROCERIES, direction: Direction = Direction.expense) =
        GeneralisationRequest(pattern = pattern, field = RuleField.counterparty, direction = direction, categoryCode = category, absorbedRuleIds = ids)

    private fun clearSuggestions(items: List<PendingMovement>) {
        pending.saveAll(
            pending.findAllById(items.map { it.id }).onEach {
                assertThat(it.status).isEqualTo(MovementStatus.pending)
                it.suggestedCategoryCode = null
                it.suggestionSource = null
            },
        )
    }

    /** Runs both at the same instant; "ok" or the refusal code for each. */
    private fun race(vararg actions: () -> Unit): List<String> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(actions.size)
        try {
            val futures = actions.map { action ->
                pool.submit<String> {
                    start.await()
                    try {
                        action()
                        "ok"
                    } catch (ex: AppException) {
                        ex.code
                    }
                }
            }
            start.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Only rows carrying the marker are refused, so the rest of the suite's rules are untouched. */
    private fun installFailOnDeleteTrigger() {
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION generalisation_test_fail_on_delete() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                IF OLD.match_value LIKE '%fail-on-delete%' THEN
                    RAISE EXCEPTION 'generalisation test: refusing delete';
                END IF;
                RETURN OLD;
            END ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute("DROP TRIGGER IF EXISTS generalisation_test_fail_on_delete ON bank_categorization_rules")
        jdbc.execute(
            "CREATE TRIGGER generalisation_test_fail_on_delete BEFORE DELETE ON bank_categorization_rules " +
                "FOR EACH ROW EXECUTE FUNCTION generalisation_test_fail_on_delete()",
        )
    }
}
