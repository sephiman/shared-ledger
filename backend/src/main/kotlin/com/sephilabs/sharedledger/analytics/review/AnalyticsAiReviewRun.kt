package com.sephilabs.sharedledger.analytics.review

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
import java.util.UUID

@Entity
@Table(name = "analytics_ai_review_runs")
class AnalyticsAiReviewRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "user_id", updatable = false)
    var userId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AiRunStatus = AiRunStatus.running,

    @Column(name = "model", nullable = false, length = 128, updatable = false)
    var model: String,

    @Column(name = "prompt_version", nullable = false, length = 32, updatable = false)
    var promptVersion: String,

    @Column(name = "locale", nullable = false, length = 2, updatable = false)
    var locale: String,

    @Column(name = "period_year", nullable = false, updatable = false)
    var periodYear: Int,

    @Column(name = "period_month", nullable = false, updatable = false)
    var periodMonth: Int,

    @Column(name = "review_kind", nullable = false, length = 24, updatable = false)
    var reviewKind: String,

    @Column(name = "days_elapsed", nullable = false, updatable = false)
    var daysElapsed: Int,

    @Column(name = "days_in_month", nullable = false, updatable = false)
    var daysInMonth: Int,

    @Column(name = "stale_spending_since", length = 7, updatable = false)
    var staleSpendingSince: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    var payloadJson: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "local_labels_json", nullable = false, columnDefinition = "jsonb")
    var localLabelsJson: String = "{}",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    var resultJson: String? = null,

    @Column(name = "findings_dropped")
    var findingsDropped: Int? = null,

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

interface AnalyticsAiReviewRunRepository : JpaRepository<AnalyticsAiReviewRun, UUID> {

    fun findAllByHouseholdIdOrderByCreatedAtDesc(householdId: UUID): List<AnalyticsAiReviewRun>

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): AnalyticsAiReviewRun?

    @Transactional
    @Modifying
    @Query(
        "UPDATE AnalyticsAiReviewRun r SET r.status = :interrupted, " +
            "r.errorCode = 'INTERRUPTED', r.finishedAt = :now " +
            "WHERE r.status = :running",
    )
    fun interruptRunning(
        @Param("now") now: Instant,
        @Param("running") running: AiRunStatus = AiRunStatus.running,
        @Param("interrupted") interrupted: AiRunStatus = AiRunStatus.interrupted,
    ): Int

    @Modifying
    @Query(value = "DELETE FROM analytics_ai_review_runs WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}

@Entity
@Table(name = "analytics_ai_review_followups")
class AnalyticsAiReviewFollowup(
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

interface AnalyticsAiReviewFollowupRepository : JpaRepository<AnalyticsAiReviewFollowup, UUID> {

    fun findAllByHouseholdIdOrderByCreatedAtAsc(householdId: UUID): List<AnalyticsAiReviewFollowup>

    fun findAllByRunIdOrderByCreatedAtAsc(runId: UUID): List<AnalyticsAiReviewFollowup>
}
