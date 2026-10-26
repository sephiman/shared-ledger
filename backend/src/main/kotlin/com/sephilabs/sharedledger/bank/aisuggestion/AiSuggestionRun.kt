package com.sephilabs.sharedledger.bank.aisuggestion

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

enum class AiSuggestionRunStatus { running, completed, failed, interrupted }

@Entity
@Table(name = "ai_suggestion_runs")
class AiSuggestionRun(
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

    @Column(name = "batches_total", nullable = false, updatable = false)
    var batchesTotal: Int,

    @Column(name = "batches_done", nullable = false)
    var batchesDone: Int = 0,

    @Column(name = "batches_failed", nullable = false)
    var batchesFailed: Int = 0,

    @Column(name = "items_sent", nullable = false)
    var itemsSent: Int = 0,

    @Column(name = "suggestions_stored", nullable = false)
    var suggestionsStored: Int = 0,

    // Below the confidence threshold or without a valid answer (unknown code, wrong direction, missing).
    @Column(name = "items_unsuggested", nullable = false)
    var itemsUnsuggested: Int = 0,

    @Column(name = "input_tokens", nullable = false)
    var inputTokens: Long = 0,

    @Column(name = "output_tokens", nullable = false)
    var outputTokens: Long = 0,

    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
)

interface AiSuggestionRunRepository : JpaRepository<AiSuggestionRun, UUID> {

    fun findFirstByHouseholdIdOrderByStartedAtDesc(householdId: UUID): AiSuggestionRun?

    /** Runs a previous process left mid-flight would otherwise block their household forever. */
    @Transactional
    @Modifying
    @Query(
        "UPDATE AiSuggestionRun r SET r.status = com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus.interrupted, " +
            "r.finishedAt = :now WHERE r.status = com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionRunStatus.running",
    )
    fun interruptRunning(@Param("now") now: Instant): Int

    @Modifying
    @Query(value = "DELETE FROM ai_suggestion_runs WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
