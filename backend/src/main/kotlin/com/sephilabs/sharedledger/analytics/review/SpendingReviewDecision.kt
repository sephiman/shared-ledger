package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.recurring.PayeeVariantClusterer
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
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class DecisionSubjectKind { recurring, merchant }

enum class SpendingReviewDecisionType { keep, compared, not_recurring, split }

@Entity
@Table(name = "spending_review_decisions")
class SpendingReviewDecision(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    val householdId: UUID = UUID.randomUUID(),

    @Column(name = "subject_key", nullable = false, length = 255)
    val subjectKey: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_kind", nullable = false, length = 32)
    val subjectKind: DecisionSubjectKind = DecisionSubjectKind.recurring,

    @Column(name = "category_code", nullable = false, length = 64)
    val categoryCode: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 32)
    var decisionType: SpendingReviewDecisionType = SpendingReviewDecisionType.keep,

    @Column(name = "display_label", nullable = false, length = 200)
    var displayLabel: String = "",

    @Column(name = "member_keys_json", nullable = false, columnDefinition = "TEXT")
    var memberKeysJson: String = "[]",

    @Column(name = "split_groups_json", columnDefinition = "TEXT")
    var splitGroupsJson: String? = null,

    @Column(name = "amount_at_decision", precision = 15, scale = 2)
    var amountAtDecision: BigDecimal? = null,

    @Column(name = "snooze_until")
    var snoozeUntil: LocalDate? = null,

    @Column(name = "decided_by_user_id", nullable = false)
    var decidedByUserId: UUID = UUID.randomUUID(),

    @Column(name = "decided_at", nullable = false)
    var decidedAt: Instant = Instant.now(),
)

interface SpendingReviewDecisionRepository : JpaRepository<SpendingReviewDecision, UUID> {
    fun findAllByHouseholdIdOrderByDecidedAtDesc(householdId: UUID): List<SpendingReviewDecision>
    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): SpendingReviewDecision?
    fun findByHouseholdIdAndSubjectKey(householdId: UUID, subjectKey: String): SpendingReviewDecision?

    @Modifying
    @Query("DELETE FROM SpendingReviewDecision d WHERE d.householdId = :householdId")
    fun hardDeleteAllByHouseholdId(@Param("householdId") householdId: UUID): Int
}

/** Pure decoded decision representation used across clustering, detection, selection, and diffing. */
data class DecodedDecision(
    val id: UUID,
    val subjectKey: String,
    val subjectKind: DecisionSubjectKind,
    val categoryCode: String,
    val decisionType: SpendingReviewDecisionType,
    val displayLabel: String,
    val memberKeys: Set<String>,
    val splitGroups: List<Set<String>>,
    val amountAtDecision: BigDecimal?,
    val snoozeUntil: LocalDate?,
    val decidedByUserId: UUID,
    val decidedAt: Instant,
) {
    /** Matches if any normalized variant key overlaps within the same category (or anchor key matches). */
    fun matches(kind: DecisionSubjectKind, categoryCode: String, candidateMemberKeys: Set<String>): Boolean {
        if (this.subjectKind != kind || this.categoryCode != categoryCode) return false
        if (candidateMemberKeys.isEmpty()) return false
        val anchor = subjectKey.substringAfterLast(':', "")
        return (anchor.isNotEmpty() && anchor in candidateMemberKeys) ||
            memberKeys.any { it in candidateMemberKeys }
    }

    /** True when a `compared` decision is still within its snooze window and has not resurfaced due to a price rise. */
    fun isComparedSnoozed(asOf: LocalDate, currentMonthlyCost: BigDecimal?): Boolean {
        if (decisionType != SpendingReviewDecisionType.compared) return false
        if (snoozeUntil != null && asOf.isAfter(snoozeUntil)) return false
        if (isComparedResurfaced(currentMonthlyCost)) return false
        return true
    }

    /** True when a `compared` decision's subject rose more than DECISION_RESURFACE_PCT above amountAtDecision. */
    fun isComparedResurfaced(currentMonthlyCost: BigDecimal?): Boolean {
        if (decisionType != SpendingReviewDecisionType.compared) return false
        val base = amountAtDecision ?: return false
        val current = currentMonthlyCost ?: return false
        if (base.signum() <= 0) return false
        val threshold = base.multiply(
            BigDecimal.ONE + SpendingReviewConstants.DECISION_RESURFACE_PCT.movePointLeft(2),
        )
        return current > threshold
    }

    fun isComparedExpired(asOf: LocalDate): Boolean =
        decisionType == SpendingReviewDecisionType.compared &&
            snoozeUntil != null &&
            asOf.isAfter(snoozeUntil)

    fun toSplitRule(): PayeeVariantClusterer.SplitRule? =
        if (decisionType == SpendingReviewDecisionType.split && splitGroups.size >= 2) {
            PayeeVariantClusterer.SplitRule(categoryCode, splitGroups)
        } else null
}

object SpendingReviewDecisionCodec {
    private val mapper = JsonMapper.builder().build()

    fun encodeKeys(keys: Collection<String>): String =
        mapper.writeValueAsString(keys.map(PayeeVariantClusterer::canonicalKey).filter { it.isNotBlank() }.distinct().sorted())

    fun encodeSplitGroups(groups: List<Collection<String>>): String =
        mapper.writeValueAsString(
            groups.map { g -> g.map(PayeeVariantClusterer::canonicalKey).filter { it.isNotBlank() }.distinct().sorted() }
                .filter { it.isNotEmpty() },
        )

    fun decode(entity: SpendingReviewDecision): DecodedDecision {
        val keys = runCatching {
            val node = mapper.readTree(entity.memberKeysJson)
            if (node.isArray) node.toList().mapNotNull { it.takeIf { s -> s.isString }?.asString() }.toSet() else emptySet()
        }.getOrDefault(emptySet())
        val groups = runCatching {
            val raw = entity.splitGroupsJson ?: return@runCatching emptyList()
            val node = mapper.readTree(raw)
            if (node.isArray) {
                node.toList().mapNotNull { arr ->
                    if (arr.isArray) arr.toList().mapNotNull { it.takeIf { s -> s.isString }?.asString() }.toSet().takeIf { it.isNotEmpty() }
                    else null
                }
            } else emptyList()
        }.getOrDefault(emptyList())
        return DecodedDecision(
            id = entity.id,
            subjectKey = entity.subjectKey,
            subjectKind = entity.subjectKind,
            categoryCode = entity.categoryCode,
            decisionType = entity.decisionType,
            displayLabel = entity.displayLabel,
            memberKeys = keys,
            splitGroups = groups,
            amountAtDecision = entity.amountAtDecision,
            snoozeUntil = entity.snoozeUntil,
            decidedByUserId = entity.decidedByUserId,
            decidedAt = entity.decidedAt,
        )
    }
}
