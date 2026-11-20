package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.ai.AiRunStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

typealias FireAiAnalysisRunStatus = AiRunStatus

@Entity
@Table(name = "fire_ai_analysis_runs")
class FireAiAnalysisRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "user_id", updatable = false)
    var userId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FireAiAnalysisRunStatus = FireAiAnalysisRunStatus.running,

    @Column(name = "model", nullable = false, length = 128, updatable = false)
    var model: String,

    @Column(name = "prompt_version", nullable = false, length = 32, updatable = false)
    var promptVersion: String,

    @Column(name = "locale", nullable = false, length = 2, updatable = false)
    var locale: String,

    @Column(name = "reference_tier", nullable = false, length = 16, updatable = false)
    var referenceTier: String,

    @Column(name = "reference_scenario_index", nullable = false, updatable = false)
    var referenceScenarioIndex: Int,

    @Column(name = "reference_scenario_name", nullable = false, length = 120, updatable = false)
    var referenceScenarioName: String,

    @Column(name = "snapshot_date", updatable = false)
    var snapshotDate: LocalDate? = null,

    @Column(name = "coverage_percent", updatable = false)
    var coveragePercent: Double? = null,

    @Column(name = "estimated_year", updatable = false)
    var estimatedYear: Int? = null,

    @Column(name = "probability", updatable = false)
    var probability: Double? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    var payloadJson: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    var resultJson: String? = null,

    @Column(name = "observations_count")
    var observationsCount: Int? = null,

    @Column(name = "data_gaps_count")
    var dataGapsCount: Int? = null,

    @Column(name = "citations_total")
    var citationsTotal: Int? = null,

    @Column(name = "citations_ungrounded")
    var citationsUngrounded: Int? = null,

    @Column(name = "input_tokens")
    var inputTokens: Int? = null,

    @Column(name = "output_tokens")
    var outputTokens: Int? = null,

    @Column(name = "reasoning_tokens")
    var reasoningTokens: Int? = null,

    @Column(name = "latency_ms")
    var latencyMs: Long? = null,

    @Column(name = "error_code", length = 64)
    var errorCode: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
)

interface FireAiAnalysisRunRepository : JpaRepository<FireAiAnalysisRun, UUID> {

    fun findAllByHouseholdIdOrderByCreatedAtDesc(householdId: UUID): List<FireAiAnalysisRun>

    fun findAllByHouseholdIdAndStatusOrderByCreatedAtDesc(
        householdId: UUID,
        status: FireAiAnalysisRunStatus,
    ): List<FireAiAnalysisRun>

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): FireAiAnalysisRun?

    @Transactional
    @Modifying
    @Query(
        "UPDATE FireAiAnalysisRun r SET r.status = :interrupted, " +
            "r.errorCode = 'INTERRUPTED', r.finishedAt = :now " +
            "WHERE r.status = :running",
    )
    fun interruptRunning(
        @Param("now") now: Instant,
        @Param("running") running: FireAiAnalysisRunStatus = FireAiAnalysisRunStatus.running,
        @Param("interrupted") interrupted: FireAiAnalysisRunStatus = FireAiAnalysisRunStatus.interrupted,
    ): Int

    @Modifying
    @Query(value = "DELETE FROM fire_ai_analysis_runs WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}

@Entity
@Table(name = "fire_ai_analysis_followups")
class FireAiAnalysisFollowup(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "run_id", nullable = false, updatable = false)
    var runId: UUID,

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "user_id", updatable = false)
    var userId: UUID?,

    @Column(name = "question", nullable = false)
    var question: String,

    @Column(name = "answer", nullable = false)
    var answer: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations_json", nullable = false, columnDefinition = "jsonb")
    var citationsJson: String = "[]",

    @Column(name = "citations_total", nullable = false)
    var citationsTotal: Int = 0,

    @Column(name = "citations_ungrounded", nullable = false)
    var citationsUngrounded: Int = 0,

    @Column(name = "input_tokens")
    var inputTokens: Int? = null,

    @Column(name = "output_tokens")
    var outputTokens: Int? = null,

    @Column(name = "reasoning_tokens")
    var reasoningTokens: Int? = null,

    @Column(name = "latency_ms")
    var latencyMs: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface FireAiAnalysisFollowupRepository : JpaRepository<FireAiAnalysisFollowup, UUID> {

    fun findAllByHouseholdIdOrderByCreatedAtAsc(householdId: UUID): List<FireAiAnalysisFollowup>

    fun findAllByRunIdOrderByCreatedAtAsc(runId: UUID): List<FireAiAnalysisFollowup>
}
