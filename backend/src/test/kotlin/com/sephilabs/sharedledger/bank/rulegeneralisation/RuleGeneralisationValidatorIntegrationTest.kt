package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.RuleSource
import com.sephilabs.sharedledger.bank.rulegeneralisation.RuleGeneralisationFixtures.Companion.GROCERIES
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID

class RuleGeneralisationValidatorIntegrationTest @Autowired constructor(
    private val fixtures: RuleGeneralisationFixtures,
    private val validator: RuleGeneralisationValidator,
) : IntegrationTestBase() {

    private fun check(
        household: RuleGeneralisationFixtures.Household,
        pattern: String,
        ids: List<UUID>,
        category: String = GROCERIES,
        direction: Direction = Direction.expense,
        field: RuleField = RuleField.counterparty,
    ) = validator.check(household.id, GeneralisationCandidate(pattern, field, direction, category, ids))

    private fun rejected(problem: ProposalProblem) = GeneralisationCheck.Rejected(problem)

    // --- Coverage -----------------------------------------------------------------------------------

    @Test
    fun `a pattern found in every absorbed value, whatever the case, is accepted without conflicts`() {
        val h = fixtures.household()

        val result = check(h, "albert HEIJN", h.ids("Albert Heijn 8591", "Albert Heijn 1077 Amsterdam"))

        assertThat(result).isInstanceOf(GeneralisationCheck.Checked::class.java)
        result as GeneralisationCheck.Checked
        assertThat(result.acceptable).isTrue()
        assertThat(result.absorbed.map { it.matchValue }).containsExactly("Albert Heijn 8591", "Albert Heijn 1077 Amsterdam")
    }

    @Test
    fun `a pattern missing from one absorbed value does not cover the group`() {
        val h = fixtures.household()

        assertThat(check(h, "Albert Heijn", h.ids("Albert Heijn 8591", "AH 8591")))
            .isEqualTo(rejected(ProposalProblem.not_covering))
    }

    @Test
    fun `surrounding spaces in the pattern are ignored, as the engine trims`() {
        val h = fixtures.household()

        assertThat(check(h, "  Vomar  ", h.ids("Vomar Filmwijk", "Vomar Almere Stad")))
            .isInstanceOf(GeneralisationCheck.Checked::class.java)
    }

    // --- Length and deny-list ---------------------------------------------------------------------

    @Test
    fun `a pattern shorter than the minimum is refused, counted after trimming`() {
        val h = fixtures.household()

        assertThat(check(h, "AH", h.ids("AH 8591", "AH to go 5501 Utrecht"))).isEqualTo(rejected(ProposalProblem.too_short))
        assertThat(check(h, " NS ", h.ids("NS Groep IZ NS Reizigers", "NS Reizigers B.V."), category = "transport.public"))
            .isEqualTo(rejected(ProposalProblem.too_short))
    }

    @Test
    fun `legal suffixes and payment-processor prefixes are refused whatever their punctuation`() {
        val h = fixtures.household()
        val bck1 = fixtures.rule(h, "BCK*Bakkerij Bart", GROCERIES)
        val bck2 = fixtures.rule(h, "BCK*Bakkerij Bart 2", GROCERIES)

        assertThat(check(h, "B.V.", h.ids("NS Reizigers B.V.", "NS Groep IZ NS Reizigers"), category = "transport.public"))
            .isIn(rejected(ProposalProblem.denied), rejected(ProposalProblem.not_covering))
        for (pattern in listOf("BCK*", "bck", "Bck*")) {
            assertThat(check(h, pattern, listOf(bck1.id, bck2.id))).`as`(pattern).isEqualTo(rejected(ProposalProblem.denied))
        }
        val nv1 = fixtures.rule(h, "Eneco N.V.", "home.utilities")
        val nv2 = fixtures.rule(h, "Ziggo N.V.", "home.utilities")
        assertThat(check(h, "N.V.", listOf(nv1.id, nv2.id), category = "home.utilities")).isEqualTo(rejected(ProposalProblem.denied))
        val z1 = fixtures.rule(h, "Zettle_*Cafe Noord", "outings.restaurants")
        val z2 = fixtures.rule(h, "Zettle_*Cafe Zuid", "outings.restaurants")
        assertThat(check(h, "Zettle", listOf(z1.id, z2.id), category = "outings.restaurants")).isEqualTo(rejected(ProposalProblem.denied))
    }

    @Test
    fun `the deny-list includes the processors added to the brief`() {
        assertThat(RULE_GENERALISATION_DENY_LIST.map(::denyKey)).contains(
            "bv", "slu", "nv", "ccv", "bck", "zettle", "sumup", "iz", "paypal", "adyen", "mollie", "stripe", "tikkie", "bizum",
        )
    }

    // --- Absorbed rules ---------------------------------------------------------------------------

    @Test
    fun `absorbed rules of different categories or directions are refused`() {
        val h = fixtures.household()

        assertThat(check(h, "Lidl", h.ids("Lidl 737 Almere", "Lidl Reisen"))).isEqualTo(rejected(ProposalProblem.mixed_rules))
        val income = fixtures.rule(h, "Vomar Almere Stad refund", "income.reimbursements", Direction.income)
        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk") + income.id)).isEqualTo(rejected(ProposalProblem.mixed_rules))
    }

    @Test
    fun `a proposal whose own category or direction differs from its rules is refused`() {
        val h = fixtures.household()

        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad"), category = "shopping.other"))
            .isEqualTo(rejected(ProposalProblem.mixed_rules))
        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad"), direction = Direction.income))
            .isEqualTo(rejected(ProposalProblem.mixed_rules))
    }

    @Test
    fun `a category outside the catalogue is refused`() {
        val h = fixtures.household()
        val a = fixtures.rule(h, "Kruidvat 1", "groceries.drugstore")
        val b = fixtures.rule(h, "Kruidvat 2", "groceries.drugstore")

        assertThat(check(h, "Kruidvat", listOf(a.id, b.id), category = "groceries.drugstore"))
            .isEqualTo(rejected(ProposalProblem.unknown_category))
    }

    @Test
    fun `an unknown id, or another household's rule, is refused as unknown`() {
        val h = fixtures.household()
        val other = fixtures.household()

        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk") + UUID.randomUUID())).isEqualTo(rejected(ProposalProblem.unknown_rule))
        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk") + other.ids("Vomar Almere Stad")))
            .isEqualTo(rejected(ProposalProblem.unknown_rule))
    }

    @Test
    fun `manual rules and contains rules of the proposal's field can be replaced`() {
        val h = fixtures.household()
        val manual = fixtures.rule(h, "Vomar Centrum", GROCERIES, priority = 100, source = RuleSource.manual)
        val contains = fixtures.rule(h, "vomar almere", GROCERIES, op = RuleOp.contains, priority = 100, source = RuleSource.manual)

        val result = check(h, "Vomar", h.ids("Vomar Filmwijk") + manual.id + contains.id)

        assertThat(result).isInstanceOf(GeneralisationCheck.Checked::class.java)
        assertThat((result as GeneralisationCheck.Checked).acceptable).isTrue()
    }

    @Test
    fun `a contains rule is only covered by a pattern found inside its own value`() {
        val h = fixtures.household()
        val contains = fixtures.rule(h, "lidl almere", GROCERIES, op = RuleOp.contains, priority = 100, source = RuleSource.manual)

        assertThat(check(h, "Lidl 737", h.ids("Lidl 737 Almere") + contains.id)).isEqualTo(rejected(ProposalProblem.not_covering))
    }

    @Test
    fun `description rules generalise into a description rule`() {
        val h = fixtures.household()
        val a = fixtures.rule(h, "/TXT/D|RECIB /Vodafone Servicios", "home.utilities", field = RuleField.description, source = RuleSource.manual, priority = 100)
        val b = fixtures.rule(h, "Vodafone Ono", "home.utilities", field = RuleField.description, op = RuleOp.contains, source = RuleSource.manual, priority = 100)

        val result = check(h, "Vodafone", listOf(a.id, b.id), category = "home.utilities", field = RuleField.description)

        assertThat(result).isInstanceOf(GeneralisationCheck.Checked::class.java)
        assertThat((result as GeneralisationCheck.Checked).acceptable).isTrue()
    }

    @Test
    fun `rules of another field, amount rules, or an amount proposal are refused`() {
        val h = fixtures.household()
        val description = fixtures.rule(h, "Vomar Almere", GROCERIES, field = RuleField.description)
        val amount = fixtures.rule(h, "0..5", GROCERIES, field = RuleField.amount, op = RuleOp.range)

        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk") + description.id)).isEqualTo(rejected(ProposalProblem.other_field))
        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk") + amount.id)).isEqualTo(rejected(ProposalProblem.other_field))
        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad"), field = RuleField.amount))
            .isEqualTo(rejected(ProposalProblem.other_field))
    }

    @Test
    fun `a group of one, the same id twice included, is refused`() {
        val h = fixtures.household()

        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk"))).isEqualTo(rejected(ProposalProblem.single_rule))
        assertThat(check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Filmwijk"))).isEqualTo(rejected(ProposalProblem.single_rule))
    }

    // --- Collisions -------------------------------------------------------------------------------

    @Test
    fun `a pattern matching another rule of the same direction and a different category collides`() {
        val h = fixtures.household()

        val result = check(h, "Lidl", h.ids("Lidl 737 Almere", "LIDL 1203 Almere Haven")) as GeneralisationCheck.Checked

        assertThat(result.collisions).containsExactly("Lidl Reisen")
        assertThat(result.acceptable).isFalse()
    }

    @Test
    fun `rules of the same category, or of the other direction, are no collision`() {
        val h = fixtures.household()
        fixtures.rule(h, "Vomar Kiosk", GROCERIES)
        fixtures.rule(h, "Vomar refund", "income.reimbursements", Direction.income)

        val result = check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad")) as GeneralisationCheck.Checked

        assertThat(result.collisions).isEmpty()
        assertThat(result.acceptable).isTrue()
    }

    @Test
    fun `a confirmed movement of the last twelve months in another category collides`() {
        val h = fixtures.household()
        fixtures.confirmed(h, "Vattenfall Warmte", "home.repairs")

        val result = check(h, "Vattenfall", h.ids("Vattenfall Klantenservice N.V.", "Vattenfall Energie"), category = "home.utilities")
            as GeneralisationCheck.Checked

        assertThat(result.collisions).containsExactly("Vattenfall Warmte")
        assertThat(result.acceptable).isFalse()
    }

    @Test
    fun `older history, the same category, the other direction and unconfirmed items are no collision`() {
        val h = fixtures.household()
        fixtures.confirmed(h, "Vattenfall Warmte", "home.repairs", bookingDate = LocalDate.now().minusMonths(13))
        fixtures.confirmed(h, "Vattenfall Energie", "home.utilities")
        fixtures.confirmed(h, "Vattenfall terugbetaling", "income.reimbursements", direction = Direction.income)

        val result = check(h, "Vattenfall", h.ids("Vattenfall Klantenservice N.V.", "Vattenfall Energie"), category = "home.utilities")
            as GeneralisationCheck.Checked

        assertThat(result.collisions).isEmpty()
    }

    @Test
    fun `collisions are counted once per counterparty across rules and history`() {
        val h = fixtures.household()
        fixtures.confirmed(h, "Lidl Reisen", "outings.travel")
        fixtures.confirmed(h, "LIDL REISEN ", "outings.travel")
        fixtures.confirmed(h, "Lidl Shop online", "shopping.other")

        val result = check(h, "Lidl", h.ids("Lidl 737 Almere", "LIDL 1203 Almere Haven")) as GeneralisationCheck.Checked

        assertThat(result.collisions).containsExactly("Lidl Reisen", "Lidl Shop online")
    }

    // --- Replay: known movements whose category would change -----------------------------------------

    @Test
    fun `rules ahead of the absorbed ones keep winning, so they cause no change`() {
        val h = fixtures.household()
        fixtures.rule(h, "PIN", "shopping.other", field = RuleField.description, op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        fixtures.pending(h, "Vomar Filmwijk", description = "PIN betaling")

        val result = check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad")) as GeneralisationCheck.Checked

        assertThat(result.changes).isEmpty()
    }

    @Test
    fun `a later rule that would win an absorbed rule value blocks the proposal`() {
        val h = fixtures.household()
        fixtures.rule(h, "almere stad", "shopping.other", op = RuleOp.contains, priority = 250, source = RuleSource.manual)

        val result = check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad")) as GeneralisationCheck.Checked

        assertThat(result.changes).containsExactly(ReplayChange("Vomar Almere Stad", null, GROCERIES, "shopping.other"))
        assertThat(result.acceptable).isFalse()
    }

    @Test
    fun `a later description rule only blocks when a known movement would actually change`() {
        val h = fixtures.household()
        fixtures.rule(h, "cadeau", "shopping.gifts", field = RuleField.description, op = RuleOp.contains, priority = 250, source = RuleSource.manual)
        val ids = h.ids("Vomar Filmwijk", "Vomar Almere Stad")

        assertThat((check(h, "Vomar", ids) as GeneralisationCheck.Checked).changes).isEmpty()

        fixtures.pending(h, "Vomar Filmwijk", description = "Cadeaukaart 25")
        assertThat((check(h, "Vomar", ids) as GeneralisationCheck.Checked).changes)
            .containsExactly(ReplayChange("Vomar Filmwijk", "Cadeaukaart 25", GROCERIES, "shopping.gifts"))
    }

    @Test
    fun `replacing early description rules is blocked where a later counterparty rule would take a known movement`() {
        val h = fixtures.household()
        val a = fixtures.rule(h, "Vodafone Servicios", "home.utilities", field = RuleField.description, op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        val b = fixtures.rule(h, "Vodafone Ono", "home.utilities", field = RuleField.description, op = RuleOp.contains, priority = 100, source = RuleSource.manual)
        fixtures.confirmed(h, "Lidl 737 Almere", "home.utilities", description = "Vodafone Servicios recibo")

        val result = check(h, "Vodafone", listOf(a.id, b.id), category = "home.utilities", field = RuleField.description)
            as GeneralisationCheck.Checked

        assertThat(result.changes).containsExactly(ReplayChange("Lidl 737 Almere", "Vodafone Servicios recibo", "home.utilities", GROCERIES))
    }

    @Test
    fun `an amount rule between the absorbed rules and the new one blocks only a real movement it would take`() {
        val h = fixtures.household()
        val a = fixtures.rule(h, "Etos 101", "health.pharmacy")
        val b = fixtures.rule(h, "Etos 202", "health.pharmacy")
        fixtures.rule(h, "0..5", "personal.other", field = RuleField.amount, op = RuleOp.range, priority = 250, source = RuleSource.manual)

        assertThat((check(h, "Etos", listOf(a.id, b.id), category = "health.pharmacy") as GeneralisationCheck.Checked).changes).isEmpty()

        fixtures.pending(h, "Etos 101", amount = "3.00")
        assertThat((check(h, "Etos", listOf(a.id, b.id), category = "health.pharmacy") as GeneralisationCheck.Checked).changes)
            .containsExactly(ReplayChange("Etos 101", "Card payment", "health.pharmacy", "personal.other"))
    }

    @Test
    fun `later rules of the same category or the other direction cause no change`() {
        val h = fixtures.household()
        fixtures.rule(h, "almere", GROCERIES, op = RuleOp.contains, priority = 250, source = RuleSource.manual)
        fixtures.rule(h, "x", "income.other", Direction.income, field = RuleField.description, op = RuleOp.contains, priority = 250)

        val result = check(h, "Vomar", h.ids("Vomar Filmwijk", "Vomar Almere Stad")) as GeneralisationCheck.Checked

        assertThat(result.changes).isEmpty()
    }
}
