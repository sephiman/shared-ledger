package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.ConfirmMovementRequest
import com.sephilabs.sharedledger.bank.PendingMovementService
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.RuleSource
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionFixtures
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.stereotype.Component
import java.time.LocalDate

/** A household whose rules look like the real one's: one learned exact rule per branch or wording. */
@Component
class RuleGeneralisationFixtures(
    private val ai: AiSuggestionFixtures,
    private val rules: CategorizationRuleRepository,
    private val pendingService: PendingMovementService,
) {

    class Household(val seeded: AiSuggestionFixtures.Seeded, val byValue: Map<String, CategorizationRule>) {
        val id get() = seeded.household.id
        fun ids(vararg values: String) = values.map { byValue.getValue(it).id }
    }

    fun household(aiEnabled: Boolean = true): Household {
        val seeded = ai.household(aiEnabled)
        val saved = REAL_RULES.map { (value, category, direction) ->
            rules.save(learned(seeded.household.id, value, category, direction))
        }
        return Household(seeded, saved.associateBy { it.matchValue })
    }

    fun rule(
        household: Household,
        value: String,
        category: String,
        direction: Direction = Direction.expense,
        field: RuleField = RuleField.counterparty,
        op: RuleOp = RuleOp.equals,
        priority: Int = LEARNED,
        source: RuleSource = RuleSource.learned,
    ): CategorizationRule = rules.save(
        CategorizationRule(
            householdId = household.id, matchField = field, matchOp = op, matchValue = value,
            categoryCode = category, direction = direction, priority = priority, source = source,
        ),
    )

    /** A movement confirmed through the real inbox path, so it carries a linked transaction. */
    fun confirmed(
        household: Household,
        counterparty: String,
        category: String,
        direction: Direction = Direction.expense,
        bookingDate: LocalDate = LocalDate.now().minusMonths(1),
        description: String = "Card payment",
    ) {
        val item = ai.item(household.seeded, counterparty, direction = direction, bookingDate = bookingDate, description = description)
        pendingService.confirm(
            household.id, item.id, ConfirmMovementRequest(categoryCode = category, saveRule = false), household.seeded.user,
        )
    }

    /** A movement still in the inbox. */
    fun pending(household: Household, counterparty: String, description: String = "Card payment", amount: String = "12.34") =
        ai.item(household.seeded, counterparty, description = description, amount = amount)

    private fun learned(householdId: java.util.UUID, value: String, category: String, direction: Direction) =
        CategorizationRule(
            householdId = householdId, matchField = RuleField.counterparty, matchOp = RuleOp.equals, matchValue = value,
            categoryCode = category, direction = direction, priority = LEARNED, source = RuleSource.learned,
        )

    companion object {
        const val LEARNED = 200
        const val GROCERIES = "groceries.groceries"

        val REAL_RULES = listOf(
            Triple("Albert Heijn 8591", GROCERIES, Direction.expense),
            Triple("Albert Heijn 1077 Amsterdam", GROCERIES, Direction.expense),
            Triple("AH 8591", GROCERIES, Direction.expense),
            Triple("BCK*AH to go 5868 Adam", GROCERIES, Direction.expense),
            Triple("AH to go 5501 Utrecht", GROCERIES, Direction.expense),
            Triple("Lidl 737 Almere", GROCERIES, Direction.expense),
            Triple("LIDL 1203 Almere Haven", GROCERIES, Direction.expense),
            Triple("Lidl Reisen", "outings.travel", Direction.expense),
            Triple("Vomar Filmwijk", GROCERIES, Direction.expense),
            Triple("Vomar Almere Stad", GROCERIES, Direction.expense),
            Triple("NS Groep IZ NS Reizigers", "transport.public", Direction.expense),
            Triple("NS Reizigers B.V.", "transport.public", Direction.expense),
            Triple("NS-Almere C.", "transport.public", Direction.expense),
            Triple("Vattenfall Klantenservice N.V.", "home.utilities", Direction.expense),
            Triple("Vattenfall Energie", "home.utilities", Direction.expense),
            Triple("Loonzorg B.V.", "income.salary", Direction.income),
            Triple("Loonzorg Salarisadministratie", "income.salary", Direction.income),
        )
    }
}
