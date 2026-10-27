package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_rule_generalisation_runs")
class RuleGeneralisationRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "user_id", updatable = false)
    var userId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AiSuggestionRunStatus = AiSuggestionRunStatus.running,

    @Column(name = "prompt_version", nullable = false, length = 16, updatable = false)
    var promptVersion: String,

    @Column(name = "model", nullable = false, length = 128, updatable = false)
    var model: String,

    // Every rule that could be sent; above RULE_GENERALISATION_MAX_RULES only the most recent go.
    @Column(name = "rules_total", nullable = false, updatable = false)
    var rulesTotal: Int,

    @Column(name = "rules_sent", nullable = false, updatable = false)
    var rulesSent: Int,

    @Column(name = "proposals_returned", nullable = false)
    var proposalsReturned: Int = 0,

    // Malformed, below the confidence threshold, or refused by validation.
    @Column(name = "proposals_discarded", nullable = false)
    var proposalsDiscarded: Int = 0,

    @Column(name = "proposals_shown", nullable = false)
    var proposalsShown: Int = 0,

    @Column(name = "proposals_accepted", nullable = false)
    var proposalsAccepted: Int = 0,

    @Column(name = "input_tokens", nullable = false)
    var inputTokens: Long = 0,

    @Column(name = "output_tokens", nullable = false)
    var outputTokens: Long = 0,

    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
)

interface RuleGeneralisationRunRepository : JpaRepository<RuleGeneralisationRun, UUID> {

    fun findFirstByHouseholdIdOrderByStartedAtDesc(householdId: UUID): RuleGeneralisationRun?

    /** Runs a previous process left mid-flight would otherwise block their household forever. */
    @Transactional
    @Modifying
    @Query(
        "UPDATE RuleGeneralisationRun r SET r.status = com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus.interrupted, " +
            "r.finishedAt = :now WHERE r.status = com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus.running",
    )
    fun interruptRunning(@Param("now") now: Instant): Int

    @Modifying
    @Query(
        "UPDATE RuleGeneralisationRun r SET r.proposalsAccepted = r.proposalsAccepted + 1 " +
            "WHERE r.id = :id AND r.householdId = :hid",
    )
    fun countAccepted(@Param("id") id: UUID, @Param("hid") householdId: UUID): Int

    @Modifying
    @Query(value = "DELETE FROM ai_rule_generalisation_runs WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
