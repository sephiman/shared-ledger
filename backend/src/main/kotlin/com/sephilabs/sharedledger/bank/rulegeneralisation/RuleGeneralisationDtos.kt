package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.CategorizationRuleDto
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus
import com.sephilabs.sharedledger.transaction.Direction
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Token figures stay out on purpose: they are for the operator's logs. */
data class RuleGeneralisationRunDto(
    val id: UUID,
    val status: AiSuggestionRunStatus,
    val rulesTotal: Int,
    val rulesSent: Int,
    val proposalsReturned: Int,
    val proposalsDiscarded: Int,
    val proposalsShown: Int,
    val proposalsAccepted: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

enum class ProposalStatus { ok, conflict, invalid }

/** A known movement whose category accepting would change; [toCategory] null when nothing would match it. */
data class ReplayChangeDto(
    val counterparty: String?,
    val description: String?,
    val fromCategory: String,
    val toCategory: String?,
)

/** The validator's verdict. Examples are capped; the counts are not. */
data class ProposalCheckDto(
    val status: ProposalStatus,
    val problem: ProposalProblem?,
    val collisionCount: Int,
    val collisionExamples: List<String>,
    val changeCount: Int,
    val changeExamples: List<ReplayChangeDto>,
)

data class AbsorbedRuleDto(val id: UUID, val matchValue: String)

data class RuleGeneralisationProposalDto(
    val id: String,
    val pattern: String,
    val field: RuleField,
    val direction: Direction,
    val categoryCode: String,
    val confidence: Double,
    val absorbed: List<AbsorbedRuleDto>,
    val check: ProposalCheckDto,
)

/** What the rules page polls. [proposals] is null until the latest run completes, and again once they expire
 *  ([proposalsExpired]). */
data class RuleGeneralisationStatusDto(
    // Counterparty and description rules: the kinds a proposal can replace.
    val eligibleRules: Int,
    val minRules: Int,
    val maxRules: Int,
    val run: RuleGeneralisationRunDto?,
    val proposals: List<RuleGeneralisationProposalDto>?,
    val proposalsExpired: Boolean,
)

/** A proposal as the member finally wants it: checked by `/validate`, written by `/accept`. [runId] only feeds
 *  the run's accepted count. */
data class GeneralisationRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 255)
    val pattern: String,
    val field: RuleField,
    val direction: Direction,
    @field:NotBlank(message = "validation.required")
    val categoryCode: String,
    val absorbedRuleIds: List<UUID>,
    val runId: UUID? = null,
)

data class AcceptedGeneralisationDto(val rule: CategorizationRuleDto, val deleted: Int)

fun RuleGeneralisationRun.toDto() = RuleGeneralisationRunDto(
    id = id,
    status = status,
    rulesTotal = rulesTotal,
    rulesSent = rulesSent,
    proposalsReturned = proposalsReturned,
    proposalsDiscarded = proposalsDiscarded,
    proposalsShown = proposalsShown,
    proposalsAccepted = proposalsAccepted,
    startedAt = startedAt,
    finishedAt = finishedAt,
)

private const val EXAMPLES = 3

fun GeneralisationCheck.toDto(): ProposalCheckDto = when (this) {
    is GeneralisationCheck.Rejected -> ProposalCheckDto(ProposalStatus.invalid, problem, 0, emptyList(), 0, emptyList())
    is GeneralisationCheck.Checked -> ProposalCheckDto(
        status = if (acceptable) ProposalStatus.ok else ProposalStatus.conflict,
        problem = null,
        collisionCount = collisions.size,
        collisionExamples = collisions.take(EXAMPLES),
        changeCount = changes.size,
        changeExamples = changes.take(EXAMPLES).map { ReplayChangeDto(it.counterparty, it.description, it.from, it.to) },
    )
}
