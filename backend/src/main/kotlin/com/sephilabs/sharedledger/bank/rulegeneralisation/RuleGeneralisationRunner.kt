package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.aisuggestion.AI_SUGGESTION_MIN_CONFIDENCE
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus
import com.sephilabs.sharedledger.bank.aisuggestion.ChatAnswer
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.PromptCatalogue
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/** One provider call per run: send the rules, judge every proposal with [RuleGeneralisationValidator], keep the
 *  ones worth showing in [RuleGeneralisationProposals]. Nothing here writes a rule. */
@Component
class RuleGeneralisationRunner(
    private val model: SuggestionModel,
    private val runs: RuleGeneralisationRunRepository,
    private val promptCatalogue: PromptCatalogue,
    private val validator: RuleGeneralisationValidator,
    private val proposals: RuleGeneralisationProposals,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    fun execute(runId: UUID, householdId: UUID, rules: List<CategorizationRule>) {
        val previous = MDC.getCopyOfContextMap()
        MDC.put("householdId", householdId.toString())
        MDC.put("aiRunId", runId.toString())
        try {
            val byPromptId = rules.mapIndexed { i, rule -> "r${i + 1}" to rule }.toMap()
            val prompt = ChatPrompt(
                system = RuleGeneralisationPrompt.system(promptCatalogue.forHousehold(householdId)),
                user = RuleGeneralisationPrompt.user(byPromptId.map { (id, r) ->
                    PromptRule(id, r.matchField, r.matchOp, r.matchValue, r.direction, r.categoryCode)
                }),
                sessionId = UUID.randomUUID(),
            )
            log.info(
                "rule_generalisation_sent run={} household={} session={} rules={} system_chars={} user_chars={}",
                runId, householdId, prompt.sessionId, rules.size, prompt.system.length, prompt.user.length,
            )
            val answer = try {
                model.complete(prompt)
            } catch (ex: SuggestionModelException) {
                log.warn("rule_generalisation_run run={} household={} attempts={} outcome=failed error={}", runId, householdId, ex.attempts, ex.message)
                finish(runId, AiSuggestionRunStatus.failed)
                return
            }
            val parsed = RuleGeneralisationParser.parse(answer.content, byPromptId.keys)
            if (!parsed.readable) {
                log.warn("rule_generalisation_run run={} household={} outcome=failed error=unreadable_answer", runId, householdId)
                finish(runId, AiSuggestionRunStatus.failed, answer)
                return
            }
            judge(runId, householdId, parsed.proposals, byPromptId, answer)
        } catch (ex: Exception) {
            log.error("rule_generalisation_run run={} household={} status=failed", runId, householdId, ex)
            finish(runId, AiSuggestionRunStatus.failed)
        } finally {
            if (previous != null) MDC.setContextMap(previous) else MDC.clear()
        }
    }

    private fun judge(
        runId: UUID,
        householdId: UUID,
        parsed: List<ParsedProposal>,
        byPromptId: Map<String, CategorizationRule>,
        answer: ChatAnswer,
    ) {
        val snapshot = validator.snapshot(householdId)
        val discarded = mutableMapOf<String, Int>()
        val shown = mutableListOf<StoredProposal>()
        var collisions = 0
        for (proposal in parsed) {
            if (proposal is ParsedProposal.Invalid) {
                discarded.merge(proposal.reason.name, 1, Int::plus)
                continue
            }
            proposal as ParsedProposal.Valid
            if (proposal.confidence < AI_SUGGESTION_MIN_CONFIDENCE) {
                discarded.merge("below_threshold", 1, Int::plus)
                continue
            }
            val absorbed = proposal.absorbs.map { byPromptId.getValue(it) }
            val candidate = GeneralisationCandidate(
                proposal.pattern, proposal.field, proposal.direction, proposal.categoryCode, absorbed.map { it.id },
            )
            when (val check = validator.check(snapshot, candidate)) {
                is GeneralisationCheck.Rejected -> discarded.merge(check.problem.name, 1, Int::plus)
                is GeneralisationCheck.Checked -> {
                    if (!check.acceptable) collisions++
                    shown += StoredProposal(
                        id = "p${shown.size + 1}",
                        pattern = proposal.pattern,
                        field = proposal.field,
                        direction = proposal.direction,
                        categoryCode = proposal.categoryCode,
                        absorbedRuleIds = candidate.absorbedRuleIds,
                        absorbedValues = absorbed.map { it.matchValue },
                        confidence = proposal.confidence,
                    )
                }
            }
        }
        proposals.put(runId, householdId, shown)
        val run = finish(runId, AiSuggestionRunStatus.completed, answer) {
            it.proposalsReturned = parsed.size
            it.proposalsDiscarded = discarded.values.sum()
            it.proposalsShown = shown.size
        }
        log.info(
            "rule_generalisation_run run={} household={} user={} status={} prompt={} model={} rules_sent={} returned={} " +
                "shown={} with_conflicts={} discarded={} attempts={} latency_ms={} input_tokens={} output_tokens={} reasoning_tokens={}",
            run.id, householdId, run.userId, run.status, run.promptVersion, run.model, run.rulesSent, parsed.size,
            shown.size, collisions, discarded, answer.attempts, answer.latencyMs, answer.inputTokens, answer.outputTokens,
            answer.reasoningTokens,
        )
    }

    private fun finish(
        runId: UUID,
        status: AiSuggestionRunStatus,
        answer: ChatAnswer? = null,
        counts: (RuleGeneralisationRun) -> Unit = {},
    ): RuleGeneralisationRun = checkNotNull(tx.execute {
        runs.findById(runId).orElseThrow().apply {
            counts(this)
            inputTokens += answer?.inputTokens ?: 0
            outputTokens += answer?.outputTokens ?: 0
            this.status = status
            finishedAt = Instant.now()
        }
    })
}
