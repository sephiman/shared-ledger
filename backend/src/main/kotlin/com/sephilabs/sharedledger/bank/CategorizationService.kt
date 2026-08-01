package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/** Deterministic categorisation rules plus learning-from-corrections: on confirm, a counterparty ->
 *  category pairing is remembered so the next movement from that payee arrives pre-categorised — but
 *  only when no existing rule already covers it (see [learn]). No ML. */
@Service
class CategorizationService(private val rules: CategorizationRuleRepository) {

    /** First matching rule's category (lowest priority number wins), scoped to the movement's direction. */
    @Transactional(readOnly = true)
    fun suggestCategory(householdId: UUID, movement: PendingMovement): String? =
        matchRule(orderedRules(householdId), movement)?.categoryCode

    /** The household's rules in priority order (lowest number first). */
    @Transactional(readOnly = true)
    fun orderedRules(householdId: UUID): List<CategorizationRule> =
        rules.findAllByHouseholdIdOrderByPriorityAsc(householdId)

    /** The first rule in [candidates] (already priority-ordered) that matches, scoped to the movement's
     *  direction. Takes a preloaded list so bulk callers load the rules once, not once per movement. */
    fun matchRule(candidates: List<CategorizationRule>, movement: PendingMovement): CategorizationRule? =
        candidates.firstOrNull { it.direction == movement.direction && it.matches(movement) }

    /** Remember counterparty -> category, but only when no existing rule already matches. An existing match
     *  means the case is covered: a confirmation that differs from the suggestion is a one-off exception for
     *  that movement, not a new norm. */
    @Transactional
    fun learn(householdId: UUID, movement: PendingMovement, categoryCode: String, direction: Direction, by: User) {
        val value = movement.counterparty?.trim().orEmpty()
        if (value.isBlank()) return
        if (matchRule(orderedRules(householdId), movement) != null) return
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

    /** Delete the household's rules among [ids]; ids of other households are silently ignored. */
    @Transactional
    fun deleteBatch(householdId: UUID, ids: List<UUID>): DeletedCountDto {
        val toDelete = rules.findAllByIdInAndHouseholdId(ids, householdId)
        rules.deleteAll(toDelete)
        return DeletedCountDto(deleted = toDelete.size)
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
        createdAt = createdAt,
    )

    private companion object {
        const val DEFAULT_PRIORITY = 100
        // Learned rules sit just below manual defaults; explicit manual rules can still override.
        const val LEARNED_PRIORITY = 200
    }
}
