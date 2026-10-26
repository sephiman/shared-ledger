package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.ConfirmMovementRequest
import com.sephilabs.sharedledger.bank.EditMovementRequest
import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingCategorisation
import com.sephilabs.sharedledger.bank.PendingMovementService
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.RuleSource
import com.sephilabs.sharedledger.bank.SuggestionSource
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** How an AI suggestion sits next to rule suggestions in the existing inbox mechanics. */
class SuggestionPrecedenceIntegrationTest @Autowired constructor(
    private val fixtures: AiSuggestionFixtures,
    private val pendingService: PendingMovementService,
    private val rules: CategorizationRuleRepository,
) : IntegrationTestBase() {

    @Test
    fun `apply rules replaces an AI suggestion with the rule's, origin and all`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "Action 1386", suggested = "groceries.groceries", source = SuggestionSource.ai, confidence = "0.92")
        rules.save(rule(seeded.household.id, "Action 1386", "home.other"))

        val result = pendingService.applyRulesToPending(seeded.household.id)

        val reloaded = fixtures.reload(item)
        assertThat(result.categorized).isEqualTo(1)
        assertThat(reloaded.suggestedCategoryCode).isEqualTo("home.other")
        assertThat(reloaded.suggestionSource).isEqualTo(SuggestionSource.rule)
        assertThat(reloaded.suggestionConfidence).isNull()
    }

    @Test
    fun `apply rules still never overwrites a rule suggestion`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "Jumbo", suggested = "groceries.groceries", source = SuggestionSource.rule)
        rules.save(rule(seeded.household.id, "Jumbo", "home.other"))

        pendingService.applyRulesToPending(seeded.household.id)

        assertThat(fixtures.reload(item).suggestedCategoryCode).isEqualTo("groceries.groceries")
    }

    @Test
    fun `a suggestion filled by apply rules is marked as coming from a rule`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "Jumbo")
        rules.save(rule(seeded.household.id, "Jumbo", "groceries.groceries"))

        pendingService.applyRulesToPending(seeded.household.id)

        assertThat(fixtures.reload(item).suggestionSource).isEqualTo(SuggestionSource.rule)
    }

    @Test
    fun `an AI suggestion with no rule covering it survives apply rules`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "Instant Gaming", suggested = "outings.hobbies", source = SuggestionSource.ai, confidence = "0.95")
        rules.save(rule(seeded.household.id, "Jumbo", "groceries.groceries"))

        pendingService.applyRulesToPending(seeded.household.id)

        val reloaded = fixtures.reload(item)
        assertThat(reloaded.suggestedCategoryCode).isEqualTo("outings.hobbies")
        assertThat(reloaded.suggestionSource).isEqualTo(SuggestionSource.ai)
    }

    @Test
    fun `the categorisation filter tells rule suggestions from AI ones and keeps both under categorized`() {
        val seeded = fixtures.household()
        val byRule = fixtures.item(seeded, "Jumbo", suggested = "groceries.groceries", source = SuggestionSource.rule)
        val byAi = fixtures.item(seeded, "ETOS", suggested = "health.pharmacy", source = SuggestionSource.ai, confidence = "0.9")
        val none = fixtures.item(seeded, "Somebody")

        fun ids(filter: PendingCategorisation?) =
            pendingService.list(seeded.household.id, MovementStatus.pending, null, null, filter, false, 0, 50)
                .items.map { it.id }.toSet()

        assertThat(ids(PendingCategorisation.suggested_by_rule)).containsExactly(byRule.id)
        assertThat(ids(PendingCategorisation.suggested_by_ai)).containsExactly(byAi.id)
        assertThat(ids(PendingCategorisation.uncategorized)).containsExactly(none.id)
        assertThat(ids(PendingCategorisation.categorized)).containsExactlyInAnyOrder(byRule.id, byAi.id)
        assertThat(ids(null)).containsExactlyInAnyOrder(byRule.id, byAi.id, none.id)
    }

    @Test
    fun `the list exposes the origin and confidence of a suggestion`() {
        val seeded = fixtures.household()
        fixtures.item(seeded, "ETOS", suggested = "health.pharmacy", source = SuggestionSource.ai, confidence = "0.93")

        val dto = pendingService.list(seeded.household.id, MovementStatus.pending, null, null, null, false, 0, 50).items.single()

        assertThat(dto.suggestionSource).isEqualTo(SuggestionSource.ai)
        assertThat(dto.suggestionConfidence).isEqualByComparingTo("0.93")
    }

    @Test
    fun `setting a category through the edit endpoint drops the AI origin`() {
        val seeded = fixtures.household()
        val item = fixtures.item(seeded, "ETOS", suggested = "health.pharmacy", source = SuggestionSource.ai, confidence = "0.93")

        pendingService.edit(seeded.household.id, item.id, EditMovementRequest(suggestedCategoryCode = "personal.personal_care"))

        val reloaded = fixtures.reload(item)
        assertThat(reloaded.suggestedCategoryCode).isEqualTo("personal.personal_care")
        assertThat(reloaded.suggestionSource).isNull()
        assertThat(reloaded.suggestionConfidence).isNull()
    }

    @Test
    fun `confirming an AI-prefilled item, edited or not, learns the usual counterparty rule`() {
        val seeded = fixtures.household()
        val asSuggested = fixtures.item(seeded, "ETOS", suggested = "health.pharmacy", source = SuggestionSource.ai, confidence = "0.93")
        val edited = fixtures.item(seeded, "Action 1386", suggested = "groceries.groceries", source = SuggestionSource.ai, confidence = "0.9")

        pendingService.confirm(seeded.household.id, asSuggested.id, ConfirmMovementRequest(), seeded.user)
        pendingService.confirm(seeded.household.id, edited.id, ConfirmMovementRequest(categoryCode = "home.other"), seeded.user)

        val learned = rules.findAllByHouseholdIdOrderByPriorityAsc(seeded.household.id).associate { it.matchValue to it.categoryCode }
        assertThat(learned).containsExactlyInAnyOrderEntriesOf(mapOf("ETOS" to "health.pharmacy", "Action 1386" to "home.other"))
        assertThat(rules.findAllByHouseholdIdOrderByPriorityAsc(seeded.household.id)).allMatch { it.source == RuleSource.learned }
    }

    private fun rule(householdId: java.util.UUID, counterparty: String, category: String) = CategorizationRule(
        householdId = householdId,
        matchField = RuleField.counterparty,
        matchOp = RuleOp.equals,
        matchValue = counterparty,
        categoryCode = category,
        direction = Direction.expense,
    )
}
