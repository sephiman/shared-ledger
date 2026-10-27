package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class RuleGeneralisationPromptTest {

    private val catalogue = listOf(
        CatalogueEntry("groceries.groceries", "Groceries", "groceries", Direction.expense, essential = true),
        CatalogueEntry("income.salary", "Salary", "income", Direction.income, essential = false),
    )
    private val json = JsonMapper.builder().build()

    private fun rule(id: String, value: String, field: RuleField = RuleField.counterparty, op: RuleOp = RuleOp.equals) =
        PromptRule(id, field, op, value, Direction.expense, "groceries.groceries")

    @Test
    fun `the system prompt states the grouping task, the pattern constraints and the output shape`() {
        val system = RuleGeneralisationPrompt.system(catalogue)

        assertThat(system)
            .contains("same merchant, brand, employer, institution or recurring payee")
            .contains("share the same direction and category")
            .contains("\"<field> contains <pattern>\"")
            .contains("Never mix counterparty and description rules in one group")
            .contains("B.V., S.L.U., N.V.")
            .contains("CCV*, BCK*, Zettle, SumUp, iZ*")
            .contains("Never propose a group of one")
            .contains("""[{"pattern": "<text>", "field": "<counterparty|description>",""")
            .contains("\"absorbs\"")
    }

    @Test
    fun `the system prompt lists the catalogue with name, group and direction`() {
        val system = RuleGeneralisationPrompt.system(catalogue)

        assertThat(system).contains("groceries.groceries | Groceries | groceries | expense")
            .contains("income.salary | Salary | income | income")
    }

    @Test
    fun `each rule goes out as one JSON line with its short id, field, match, value, direction and category`() {
        val user = RuleGeneralisationPrompt.user(listOf(rule("r1", "Albert Heijn 8591"), rule("r2", "lidl", op = RuleOp.contains)))

        val lines = user.lines().drop(1)
        assertThat(lines).hasSize(2)
        assertThat(json.readTree(lines[0]).properties().associate { it.key to it.value.asString() }).isEqualTo(
            mapOf(
                "id" to "r1", "field" to "counterparty", "match" to "equals", "value" to "Albert Heijn 8591",
                "direction" to "expense", "category" to "groceries.groceries",
            ),
        )
        assertThat(json.readTree(lines[1])["match"].asString()).isEqualTo("contains")
    }

    @Test
    fun `IBAN-shaped tokens in a rule value never leave`() {
        val user = RuleGeneralisationPrompt.user(listOf(rule("r1", "Loonzorg NL91ABNA0417164300")))

        assertThat(user).doesNotContain("NL91ABNA0417164300").contains("Loonzorg [IBAN]")
    }
}
