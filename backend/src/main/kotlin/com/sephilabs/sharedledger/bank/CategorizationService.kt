package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * Configurable categorisation rules plus learning-from-corrections. Applied during sync to suggest
 * a category; on confirm, a counterparty -> category pairing is remembered as a learned rule so the
 * next movement from the same payee arrives pre-categorised. No ML — just deterministic matching.
 */
@Service
class CategorizationService(private val rules: CategorizationRuleRepository) {

    /** First matching rule's category (lowest priority number wins), scoped to the movement's direction. */
    @Transactional(readOnly = true)
    fun suggestCategory(householdId: UUID, movement: PendingMovement): String? =
        rules.findAllByHouseholdIdOrderByPriorityAsc(householdId)
            .firstOrNull { it.direction == movement.direction && it.matches(movement) }
            ?.categoryCode

    /** Remember counterparty -> category for next time (idempotent upsert of a learned rule). */
    @Transactional
    fun learn(householdId: UUID, counterparty: String?, categoryCode: String, direction: Direction, by: User) {
        val value = counterparty?.trim().orEmpty()
        if (value.isBlank()) return
        val existing = rules.findFirstByHouseholdIdAndMatchFieldAndMatchOpAndMatchValueIgnoreCase(
            householdId, RuleField.counterparty, RuleOp.equals, value,
        )
        if (existing != null) {
            existing.categoryCode = categoryCode
            existing.direction = direction
            return
        }
        rules.save(
            CategorizationRule(
                householdId = householdId,
                matchField = RuleField.counterparty,
                matchOp = RuleOp.equals,
                matchValue = value,
                categoryCode = categoryCode,
                direction = direction,
                priority = LEARNED_PRIORITY,
                source = RuleSource.learned,
                createdByUserId = by.id,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<CategorizationRuleDto> =
        rules.findAllByHouseholdIdOrderByPriorityAsc(householdId).map { it.toDto() }

    @Transactional
    fun create(householdId: UUID, request: CategorizationRuleRequest, by: User): CategorizationRuleDto =
        rules.save(
            CategorizationRule(
                householdId = householdId,
                matchField = request.matchField,
                matchOp = request.matchOp,
                matchValue = request.matchValue.trim(),
                categoryCode = request.categoryCode,
                direction = request.direction,
                priority = request.priority ?: DEFAULT_PRIORITY,
                source = RuleSource.manual,
                createdByUserId = by.id,
            ),
        ).toDto()

    @Transactional
    fun update(householdId: UUID, id: UUID, request: CategorizationRuleRequest): CategorizationRuleDto {
        val rule = rules.findByIdAndHouseholdId(id, householdId) ?: throw AppException.notFound("BANK_RULE_NOT_FOUND")
        rule.matchField = request.matchField
        rule.matchOp = request.matchOp
        rule.matchValue = request.matchValue.trim()
        rule.categoryCode = request.categoryCode
        rule.direction = request.direction
        rule.priority = request.priority ?: rule.priority
        return rule.toDto()
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID) {
        val rule = rules.findByIdAndHouseholdId(id, householdId) ?: throw AppException.notFound("BANK_RULE_NOT_FOUND")
        rules.delete(rule)
    }

    private fun CategorizationRule.matches(m: PendingMovement): Boolean = when (matchField) {
        RuleField.counterparty -> textMatches(m.counterparty)
        RuleField.description -> textMatches(m.description)
        RuleField.amount -> amountMatches(m.amount)
    }

    private fun CategorizationRule.textMatches(target: String?): Boolean {
        val value = target?.trim()?.lowercase() ?: return false
        val expected = matchValue.trim().lowercase()
        return when (matchOp) {
            RuleOp.equals -> value == expected
            RuleOp.contains -> value.contains(expected)
            RuleOp.range -> false
        }
    }

    private fun CategorizationRule.amountMatches(amount: BigDecimal): Boolean = when (matchOp) {
        RuleOp.equals -> runCatching { BigDecimal(matchValue.trim()).compareTo(amount) == 0 }.getOrDefault(false)
        RuleOp.range -> {
            val parts = matchValue.split("..")
            if (parts.size != 2) false
            else runCatching {
                val min = BigDecimal(parts[0].trim())
                val max = BigDecimal(parts[1].trim())
                amount >= min && amount <= max
            }.getOrDefault(false)
        }
        RuleOp.contains -> false
    }

    private fun CategorizationRule.toDto() = CategorizationRuleDto(
        id = id,
        matchField = matchField,
        matchOp = matchOp,
        matchValue = matchValue,
        categoryCode = categoryCode,
        direction = direction,
        priority = priority,
        source = source,
    )

    private companion object {
        const val DEFAULT_PRIORITY = 100
        // Learned rules sit just below manual defaults; explicit manual rules can still override.
        const val LEARNED_PRIORITY = 200
    }
}
