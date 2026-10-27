package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.CategorizationService
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.aisuggestion.AiAvailability
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettingsService
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

const val RULE_GENERALISATION_MIN_RULES = 2
const val RULE_GENERALISATION_MAX_RULES = 300

/** "Suggest generalised rules": gated exactly like AI suggestions (no provider → 404, setting off → 409). */
@Service
class RuleGeneralisationService(
    private val availability: AiAvailability,
    private val settings: AiSuggestionSettingsService,
    private val rules: CategorizationRuleRepository,
    private val categorization: CategorizationService,
    private val runs: RuleGeneralisationRunRepository,
    private val runner: RuleGeneralisationRunner,
    private val validator: RuleGeneralisationValidator,
    private val proposals: RuleGeneralisationProposals,
    private val messages: Messages,
    @Qualifier("aiSuggestionExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Proposals are re-validated on every read: accepting one, or any rule edit, can change the others. */
    @Transactional(readOnly = true)
    fun status(householdId: UUID): RuleGeneralisationStatusDto {
        requireOn(householdId)
        val all = rules.findAllByHouseholdIdOrderByPriorityAsc(householdId)
        val run = runs.findFirstByHouseholdIdOrderByStartedAtDesc(householdId)
        val stored = run?.takeIf { it.status == AiSuggestionRunStatus.completed }?.let { proposals.get(it.id, householdId) }
        return RuleGeneralisationStatusDto(
            eligibleRules = all.count { it.isGeneralisable() },
            minRules = RULE_GENERALISATION_MIN_RULES,
            maxRules = RULE_GENERALISATION_MAX_RULES,
            run = run?.toDto(),
            proposals = stored?.let { list ->
                val snapshot = validator.snapshot(householdId)
                list.map { it.toDto(validator.check(snapshot, it.toCandidate()).toDto()) }
            },
            proposalsExpired = run?.status == AiSuggestionRunStatus.completed && stored == null,
        )
    }

    /** Snapshots the rules to send and hands them to the runner. Not transactional: the run row must be committed
     *  before the worker reads it. Amount rules never leave: their values are amounts. */
    fun start(householdId: UUID, by: User): RuleGeneralisationRunDto {
        val provider = requireOn(householdId, by)
        val all = rules.findAllByHouseholdIdOrderByPriorityAsc(householdId)
        if (all.count { it.isGeneralisable() } < RULE_GENERALISATION_MIN_RULES) {
            throw rejected(householdId, by, "RULE_GENERALISATION_TOO_FEW_RULES")
        }
        val sendable = all.filter { it.matchField != RuleField.amount }.sortedByDescending { it.createdAt }
        val sent = sendable.take(RULE_GENERALISATION_MAX_RULES)
        val run = try {
            runs.saveAndFlush(
                RuleGeneralisationRun(
                    householdId = householdId,
                    userId = by.id,
                    promptVersion = RuleGeneralisationPrompt.VERSION,
                    model = provider.model,
                    rulesTotal = sendable.size,
                    rulesSent = sent.size,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            throw rejected(householdId, by, "RULE_GENERALISATION_RUN_IN_PROGRESS")
        }
        log.info(
            "rule_generalisation_run_started run={} household={} user={} rules_total={} rules_sent={} model={} prompt={}",
            run.id, householdId, by.id, sendable.size, sent.size, run.model, run.promptVersion,
        )
        executor.execute { runner.execute(run.id, householdId, sent) }
        return runs.findById(run.id).orElseThrow().toDto()
    }

    @Transactional(readOnly = true)
    fun validate(householdId: UUID, request: GeneralisationRequest): ProposalCheckDto {
        requireOn(householdId)
        return validator.check(householdId, request.toCandidate()).toDto()
    }

    /** Re-validates under a household lock and the absorbed rows' locks, then writes: one transaction, so on any
     *  failure neither the new rule nor the deletions happen. */
    @Transactional
    fun accept(householdId: UUID, request: GeneralisationRequest, by: User): AcceptedGeneralisationDto {
        requireOn(householdId, by)
        rules.lockRulesOf(householdId)
        rules.lockAllByIdInAndHouseholdId(request.absorbedRuleIds, householdId)
        val check = validator.check(householdId, request.toCandidate())
        val checked = when (check) {
            is GeneralisationCheck.Rejected -> throw refusal(householdId, by, check.problem)
            is GeneralisationCheck.Checked -> check
        }
        if (checked.collisions.isNotEmpty()) {
            throw rejected(householdId, by, "RULE_GENERALISATION_COLLISION", checked.collisions.size)
        }
        if (checked.changes.isNotEmpty()) {
            throw rejected(householdId, by, "RULE_GENERALISATION_PRECEDENCE", checked.changes.size)
        }
        val rule = categorization.generalise(
            householdId, request.field, request.pattern, request.categoryCode, request.direction, checked.absorbed, by,
        )
        request.runId?.let { runs.countAccepted(it, householdId) }
        log.info(
            "rule_generalisation_accepted household={} user={} run={} rule={} absorbed={}",
            householdId, by.id, request.runId, rule.id, checked.absorbed.size,
        )
        return AcceptedGeneralisationDto(rule = rule, deleted = checked.absorbed.size)
    }

    private fun refusal(householdId: UUID, by: User, problem: ProposalProblem): AppException {
        if (problem == ProposalProblem.unknown_rule) return rejected(householdId, by, "RULE_GENERALISATION_STALE")
        log.info("rule_generalisation_refused household={} user={} reason={}", householdId, by.id, problem)
        val field = when (problem) {
            ProposalProblem.too_short, ProposalProblem.denied, ProposalProblem.not_covering -> "pattern"
            ProposalProblem.unknown_category -> "categoryCode"
            ProposalProblem.other_field -> "field"
            else -> "absorbedRuleIds"
        }
        val key = "RULE_GENERALISATION_PROBLEM_${problem.name.uppercase()}"
        return AppException.badRequest("VALIDATION_FAILED", fields = mapOf(field to messages.resolve(key)))
    }

    private fun requireOn(householdId: UUID, by: User? = null) = availability.requireConfigured().also {
        if (!settings.isEnabled(householdId)) {
            throw by?.let { rejected(householdId, it, "AI_SUGGESTIONS_DISABLED") } ?: AppException.conflict("AI_SUGGESTIONS_DISABLED")
        }
    }

    private fun rejected(householdId: UUID, by: User, code: String, vararg args: Any): AppException {
        log.info("rule_generalisation_rejected household={} user={} reason={}", householdId, by.id, code)
        return AppException.conflict(code, *args)
    }

    private fun GeneralisationRequest.toCandidate() =
        GeneralisationCandidate(pattern, field, direction, categoryCode, absorbedRuleIds)

    private fun StoredProposal.toCandidate() = GeneralisationCandidate(pattern, field, direction, categoryCode, absorbedRuleIds)

    private fun StoredProposal.toDto(check: ProposalCheckDto) = RuleGeneralisationProposalDto(
        id = id,
        pattern = pattern,
        field = field,
        direction = direction,
        categoryCode = categoryCode,
        confidence = confidence,
        absorbed = absorbedRuleIds.zip(absorbedValues) { id, value -> AbsorbedRuleDto(id, value) },
        check = check,
    )

    @EventListener(ApplicationReadyEvent::class)
    fun interruptLeftoverRuns() {
        val interrupted = runs.interruptRunning(Instant.now())
        if (interrupted > 0) log.warn("rule_generalisation_runs_interrupted count={}", interrupted)
    }
}
