package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.CategorizationService
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.textMatches
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

const val RULE_GENERALISATION_MIN_PATTERN_LENGTH = 3
const val RULE_GENERALISATION_COLLISION_MONTHS = 12L

/** The fields a generalised rule can match on: the text ones. */
val GENERALISABLE_FIELDS = setOf(RuleField.counterparty, RuleField.description)

/** Compared by [denyKey], so "B.V.", "bv" and "BV " are one entry. */
val RULE_GENERALISATION_DENY_LIST: Set<String> = setOf(
    "B.V.", "S.L.U.", "N.V.", "S.L.", "S.A.", "GmbH", "Ltd",
    "CCV*", "BCK*", "Zettle", "SumUp", "iZ*", "PayPal", "Adyen", "Mollie", "Stripe", "Tikkie", "Bizum",
)

fun denyKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

private val DENIED_KEYS = RULE_GENERALISATION_DENY_LIST.map(::denyKey).toSet()

/** A text rule of either match type can be replaced, whoever made it. */
fun CategorizationRule.isGeneralisable() = matchField in GENERALISABLE_FIELDS && matchOp != RuleOp.range

data class GeneralisationCandidate(
    val pattern: String,
    val field: RuleField,
    val direction: Direction,
    val categoryCode: String,
    val absorbedRuleIds: List<UUID>,
)

enum class ProposalProblem {
    single_rule, unknown_rule, other_field, mixed_rules, unknown_category, too_short, denied, not_covering,
}

/** A known movement whose category the proposal would change. */
data class ReplayChange(val counterparty: String?, val description: String?, val from: String, val to: String?)

sealed interface GeneralisationCheck {
    /** Not a usable proposal at all: discarded from a run, a 400 (or 409 for [ProposalProblem.unknown_rule]) on accept. */
    data class Rejected(val problem: ProposalProblem) : GeneralisationCheck

    /** Covers its rules; accept stays blocked while [collisions] or [changes] exist. */
    data class Checked(
        val absorbed: List<CategorizationRule>,
        val collisions: List<String>,
        val changes: List<ReplayChange>,
    ) : GeneralisationCheck {
        val acceptable: Boolean get() = collisions.isEmpty() && changes.isEmpty()
    }
}

/** What a check reads, loaded once so a run's proposals are all judged against the same state. [rules] is in
 *  the engine's own order. */
class GeneralisationSnapshot(
    val householdId: UUID,
    val rules: List<CategorizationRule>,
    val history: List<ConfirmedCategory>,
    val movements: List<PendingMovement>,
    val catalogue: Map<String, Direction>,
)

/** The deterministic half of the feature: the model proposes, this decides. The new rule goes after every rule
 *  of its direction, so it can never take a movement another rule wins today. What can still change is who wins
 *  where an absorbed rule used to: [replay] runs the engine before and after over every movement the household
 *  knows — inbox, the last months' confirmed items, and every rule value as a synthetic one — and any category
 *  that changes blocks the proposal. It proves "nothing you have seen changes", not every possible string. */
@Component
class RuleGeneralisationValidator(
    private val rules: CategorizationRuleRepository,
    private val history: ConfirmedCategoryHistory,
    private val categories: CategoryService,
    private val engine: CategorizationService,
) {

    @Transactional(readOnly = true)
    fun snapshot(householdId: UUID): GeneralisationSnapshot {
        val since = LocalDate.now().minusMonths(RULE_GENERALISATION_COLLISION_MONTHS)
        return GeneralisationSnapshot(
            householdId = householdId,
            rules = rules.findAllByHouseholdIdOrderByPriorityAsc(householdId),
            history = history.confirmedSince(householdId, since),
            movements = history.knownMovements(householdId, since),
            catalogue = categories.listForHousehold(householdId).associate { it.code to Direction.valueOf(it.kind) },
        )
    }

    @Transactional(readOnly = true)
    fun check(householdId: UUID, candidate: GeneralisationCandidate): GeneralisationCheck =
        check(snapshot(householdId), candidate)

    fun check(snapshot: GeneralisationSnapshot, candidate: GeneralisationCandidate): GeneralisationCheck {
        val ids = candidate.absorbedRuleIds.distinct()
        if (ids.size < 2) return GeneralisationCheck.Rejected(ProposalProblem.single_rule)
        val byId = snapshot.rules.associateBy { it.id }
        val absorbed = ids.map { byId[it] ?: return GeneralisationCheck.Rejected(ProposalProblem.unknown_rule) }
        problemWith(absorbed, candidate, snapshot.catalogue)?.let { return GeneralisationCheck.Rejected(it) }
        val others = snapshot.rules.filter { it.id !in ids && it.direction == candidate.direction }
        return GeneralisationCheck.Checked(
            absorbed = absorbed,
            collisions = collisions(candidate, others, snapshot.history),
            changes = replay(snapshot, candidate, absorbed),
        )
    }

    private fun problemWith(
        absorbed: List<CategorizationRule>,
        candidate: GeneralisationCandidate,
        catalogue: Map<String, Direction>,
    ): ProposalProblem? {
        val pattern = candidate.pattern.trim()
        return when {
            candidate.field !in GENERALISABLE_FIELDS ||
                absorbed.any { !it.isGeneralisable() || it.matchField != candidate.field } -> ProposalProblem.other_field
            absorbed.any { it.categoryCode != candidate.categoryCode || it.direction != candidate.direction } ->
                ProposalProblem.mixed_rules
            catalogue[candidate.categoryCode] != candidate.direction -> ProposalProblem.unknown_category
            pattern.length < RULE_GENERALISATION_MIN_PATTERN_LENGTH -> ProposalProblem.too_short
            denyKey(pattern).let { it.isEmpty() || it in DENIED_KEYS } -> ProposalProblem.denied
            // Found inside an equals value, the pattern matches that text; inside a contains value, it matches
            // every text that value matched.
            absorbed.any { !textMatches(RuleOp.contains, pattern, it.matchValue) } -> ProposalProblem.not_covering
            else -> null
        }
    }

    /** Texts of the proposal's field the pattern would reach that this household files elsewhere: other rules'
     *  values and confirmed movements, once per text (rules first). Breadth, not precedence — a new variant of
     *  these would land in the wrong category. */
    private fun collisions(
        candidate: GeneralisationCandidate,
        others: List<CategorizationRule>,
        history: List<ConfirmedCategory>,
    ): List<String> {
        val fromRules = others
            .filter { it.matchField == candidate.field && it.categoryCode != candidate.categoryCode }
            .map { it.matchValue }
        val fromHistory = history
            .filter { it.direction == candidate.direction.name && it.categoryCode != candidate.categoryCode }
            .mapNotNull { if (candidate.field == RuleField.counterparty) it.counterparty else it.description }
        return (fromRules + fromHistory)
            .filter { textMatches(RuleOp.contains, candidate.pattern, it) }
            .map { it.trim() }
            .distinctBy { it.lowercase() }
    }

    private fun replay(
        snapshot: GeneralisationSnapshot,
        candidate: GeneralisationCandidate,
        absorbed: List<CategorizationRule>,
    ): List<ReplayChange> {
        val generalised = CategorizationRule(
            householdId = snapshot.householdId,
            matchField = candidate.field,
            matchOp = RuleOp.contains,
            matchValue = candidate.pattern.trim(),
            categoryCode = candidate.categoryCode,
            direction = candidate.direction,
            priority = Int.MAX_VALUE,
        )
        val absorbedIds = absorbed.map { it.id }.toSet()
        val before = snapshot.rules
        // Last of its direction, as CategorizationService.generalise writes it.
        val after = before.filter { it.id !in absorbedIds } + generalised
        val touched = absorbed + generalised
        fun changes(probes: List<PendingMovement>, beforeRules: List<CategorizationRule>, afterRules: List<CategorizationRule>) =
            probes
                // Only a movement an absorbed rule or the new one matches can change hands.
                .filter { m -> touched.any { engine.matchRule(listOf(it), m) != null } }
                .mapNotNull { m ->
                    val was = engine.matchRule(beforeRules, m)?.categoryCode ?: return@mapNotNull null
                    val now = engine.matchRule(afterRules, m)?.categoryCode
                    if (now == was) null else ReplayChange(m.counterparty, m.description, was, now)
                }
        // Synthetic movements carry no amount, so amount rules sit out of their replay, before and after alike.
        val textOnly = { list: List<CategorizationRule> -> list.filter { it.matchField != RuleField.amount } }
        val real = changes(snapshot.movements.filter { it.direction == candidate.direction }, before, after)
        val synthetic = changes(syntheticMovements(snapshot, candidate.direction), textOnly(before), textOnly(after))
        return (real + synthetic).distinctBy { listOf(it.counterparty?.lowercase(), it.description?.lowercase()) }
    }

    /** One movement per text rule value of the direction, carrying it in that rule's field. Never persisted. */
    private fun syntheticMovements(snapshot: GeneralisationSnapshot, direction: Direction): List<PendingMovement> =
        snapshot.rules.filter { it.direction == direction && it.isGeneralisable() }.map {
            PendingMovement(
                householdId = snapshot.householdId,
                connectionId = SYNTHETIC,
                accountId = SYNTHETIC,
                bankMovementId = "replay",
                bookingDate = LocalDate.now(),
                direction = direction,
                amount = BigDecimal.ZERO,
                counterparty = it.matchValue.takeIf { _ -> it.matchField == RuleField.counterparty },
                description = it.matchValue.takeIf { _ -> it.matchField == RuleField.description },
            )
        }

    private companion object {
        val SYNTHETIC: UUID = UUID(0, 0)
    }
}
