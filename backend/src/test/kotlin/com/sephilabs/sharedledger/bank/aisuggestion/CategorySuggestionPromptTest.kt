package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.YearMonth

class CategorySuggestionPromptTest {

    private val catalogue = listOf(
        CatalogueEntry("groceries.groceries", "Groceries", "groceries", Direction.expense, essential = true),
        CatalogueEntry("income.salary", "Salary", "income", Direction.income, essential = false),
        CatalogueEntry("shopping.vehiculo", "Vehiculo", "shopping", Direction.expense, essential = false),
    )

    private fun rule(value: String, category: String, field: RuleField = RuleField.counterparty) =
        RuleExample(field, RuleOp.equals, value, Direction.expense, category)

    @Test
    fun `the system prompt lists the whole catalogue with name, group, direction and essential flag`() {
        val system = CategorySuggestionPrompt.system(catalogue, emptyList())

        assertThat(system).contains("code | name | group | direction | essential")
        assertThat(system).contains("groceries.groceries | Groceries | groceries | expense | yes")
        assertThat(system).contains("income.salary | Salary | income | income | no")
        assertThat(system).contains("shopping.vehiculo | Vehiculo | shopping | expense | no")
    }

    @Test
    fun `the system prompt states the task, the field weighting and the output format in that order`() {
        val system = CategorySuggestionPrompt.system(catalogue, listOf(rule("Jumbo", "groceries.groceries")))

        val task = system.indexOf("what kind of merchant or concept it represents")
        val weighting = system.indexOf("the counterparty is the primary signal")
        val catalogueAt = system.indexOf("## Category catalogue")
        val rules = system.indexOf("EXAMPLES of the household's vocabulary and habits, not a lookup table")
        val output = system.indexOf("## Output")
        assertThat(listOf(task, weighting, catalogueAt, rules, output)).allMatch { it >= 0 }.isSorted
        assertThat(system).contains("the description is the main signal")
        assertThat(system).contains("An expense movement must get an expense category")
        assertThat(system).contains("""[{"id": "<movement id>", "categoryCode": "<code from the catalogue>", "confidence": <0.0-1.0>}]""")
    }

    @Test
    fun `rules are sampled evenly across categories up to the cap`() {
        val rules = (1..10).map { rule("Shop $it", "groceries.groceries") } +
            (1..2).map { rule("Bar $it", "outings.restaurants") } +
            listOf(rule("Employer", "income.salary"))

        val sampled = CategorySuggestionPrompt.sampleRules(rules, cap = 6)

        assertThat(sampled).hasSize(6)
        assertThat(sampled.count { it.categoryCode == "outings.restaurants" }).isEqualTo(2)
        assertThat(sampled.count { it.categoryCode == "income.salary" }).isEqualTo(1)
        assertThat(sampled.count { it.categoryCode == "groceries.groceries" }).isEqualTo(3)
    }

    @Test
    fun `amount rules never reach the prompt`() {
        val rules = listOf(
            rule("Albert Heijn", "groceries.groceries"),
            rule("10..50", "groceries.groceries", field = RuleField.amount),
        )

        val system = CategorySuggestionPrompt.system(catalogue, rules)

        assertThat(system).contains("counterparty | equals | Albert Heijn | expense | groceries.groceries")
        assertThat(system).doesNotContain("10..50")
    }

    @Test
    fun `each movement carries exactly id, counterparty, description, direction and booking month`() {
        val user = CategorySuggestionPrompt.user(
            listOf(PromptMovement("m1", "Vomar Filmwijk", "Vomar ALMERE Google Pay", Direction.expense, YearMonth.of(2026, 9))),
        )

        val line = user.lines()[1]
        @Suppress("UNCHECKED_CAST")
        val fields = JsonMapper.builder().build().readValue(line, Map::class.java) as Map<String, Any?>
        assertThat(fields).containsExactlyEntriesOf(
            linkedMapOf(
                "id" to "m1",
                "counterparty" to "Vomar Filmwijk",
                "description" to "Vomar ALMERE Google Pay",
                "direction" to "expense",
                "bookingMonth" to "2026-09",
            ),
        )
    }

    @Test
    fun `IBANs are removed from counterparty, description and rule values, compact or grouped`() {
        val user = CategorySuggestionPrompt.user(
            listOf(
                PromptMovement(
                    "m1",
                    "NL91ABNA0417164300",
                    "Naam: Instant Gaming IBAN: NL04ADYB2017400157 Kenmerk: 16-09-2026 Valutadatum: 16-09-2026",
                    Direction.expense,
                    YearMonth.of(2026, 9),
                ),
                PromptMovement("m2", "Elena", "/TXT/H|PAGO BIZUM ES91 2100 0418 4502 0005 1332", Direction.income, YearMonth.of(2026, 9)),
            ),
        )
        val system = CategorySuggestionPrompt.system(catalogue, listOf(rule("DE90700202700020129028", "groceries.groceries", RuleField.description)))

        for (iban in listOf("NL91ABNA0417164300", "NL04ADYB2017400157", "ES91 2100", "DE90700202700020129028")) {
            assertThat(user + system).doesNotContain(iban)
        }
        assertThat(user).contains("Naam: Instant Gaming IBAN: [IBAN] Kenmerk: 16-09-2026")
        assertThat(user).contains("PAGO BIZUM [IBAN]")
    }

    @Test
    fun `card and reference numbers that are not IBAN-shaped are kept`() {
        val text = "Kaartnr: 4556 18** **** 9698 Term: 03MC02 Kenmerk: GC0120271500-20260903085831"
        assertThat(IbanRedaction.redact(text)).isEqualTo(text)
    }
}
