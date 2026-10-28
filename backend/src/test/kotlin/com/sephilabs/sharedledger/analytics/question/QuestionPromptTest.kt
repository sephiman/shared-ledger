package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class QuestionPromptTest {

    private val catalogue = listOf(
        CatalogueEntry("outings.restaurants", "Restaurants", "outings", Direction.expense, essential = false),
        CatalogueEntry("groceries.groceries", "Groceries", "groceries", Direction.expense, essential = true),
        CatalogueEntry("income.salary", "Salary", null, Direction.income, essential = false),
    )
    private val today = LocalDate.of(2026, 10, 28)
    private val system = QuestionPrompt.system(catalogue, today, QuestionLanguage.en)

    @Test
    fun `the system prompt lists the whole catalogue with code, name, group and direction`() {
        assertThat(system).contains("code | name | group | direction")
        assertThat(system).contains("outings.restaurants | Restaurants | outings | expense")
        assertThat(system).contains("groceries.groceries | Groceries | groceries | expense")
        assertThat(system).contains("income.salary | Salary |  | income")
    }

    @Test
    fun `the system prompt carries today's date, its month end and the member's language`() {
        val spanish = QuestionPrompt.system(catalogue, today, QuestionLanguage.es)

        assertThat(spanish).contains("Today is 2026-10-28 (Wednesday)", "2026-10-31", "Spanish")
        assertThat(system).contains("English")
    }

    @Test
    fun `the system prompt names every filter that does not exist, so it is reported rather than forced`() {
        val missing = system.substringAfter("## Filters that do NOT exist").substringBefore("##")

        assertThat(missing).contains("member", "account", "merchant", "free text", "amount", "\"unsupported\"")
    }

    @Test
    fun `the model returns filters only, never a view to show them in`() {
        val output = system.substringAfter("## Output")

        assertThat(output).contains("\"filters\"", "\"unsupported\"", "\"confidence\"")
        assertThat(output).doesNotContain("\"target\"")
        assertThat(system).contains("\"direction\"", "\"categories\"", "\"range\"", "\"isRefund\"", "\"priorYear\"")
        assertThat(system).doesNotContain("\"explorer\"", "\"transactions\"")
        QuestionSchema.RANGE_PRESETS.forEach { assertThat(system).contains("\"$it\"") }
        assertThat(system).contains("Never answer the question itself", "never invent a category")
    }

    @Test
    fun `several categories and a comparison with the year before are filters, not unsupported`() {
        val missing = system.substringAfter("## Filters that do NOT exist").substringBefore("##")

        assertThat(missing).doesNotContain("several categories", "year before")
        assertThat(system).contains("several categories or groups", "the same period a year earlier")
    }

    @Test
    fun `an open-ended period with nothing else means all time up to today, while so far this year is year to date`() {
        assertThat(system).contains("\"so far\"", "\"to date\"", "\"hasta ahora\"")
        assertThat(system).contains("mean {\"preset\": \"all\"}")
        assertThat(system).contains("\"so far this year\"").contains("is {\"preset\": \"ytd\"}")
    }

    @Test
    fun `the user message is the question alone`() {
        assertThat(QuestionPrompt.user("  How much on Restaurants last summer?  ")).isEqualTo("How much on Restaurants last summer?")
    }
}
