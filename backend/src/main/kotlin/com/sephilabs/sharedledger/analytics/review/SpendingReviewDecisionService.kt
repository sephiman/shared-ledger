package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.recurring.PayeeVariantClusterer
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Household-wide decision memory for the Spending review: keep, compared, not_recurring, and split. */
@Service
class SpendingReviewDecisionService(
    private val repo: SpendingReviewDecisionRepository,
    private val dataQueries: SpendingReviewDataQueries,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun listDecoded(householdId: UUID): List<DecodedDecision> =
        repo.findAllByHouseholdIdOrderByDecidedAtDesc(householdId).map(SpendingReviewDecisionCodec::decode)

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<SpendingReviewDecisionDto> {
        val decoded = listDecoded(householdId)
        val emails = dataQueries.userEmails(decoded.map { it.decidedByUserId })
        val today = LocalDate.now(clock)
        return decoded.map { d ->
            val status = when (d.decisionType) {
                SpendingReviewDecisionType.compared -> if (d.isComparedExpired(today)) "expired" else "snoozed"
                else -> "active"
            }
            d.toDto(status = status, currentMonthlyCost = null, decidedBy = emails[d.decidedByUserId].orEmpty())
        }
    }

    /** Evaluates all household decisions against the current review's candidates & observed payees,
     *  tagging unmatched decisions as `inactive` rather than dropping them. */
    fun evaluateAgainst(
        decisions: List<DecodedDecision>,
        recurring: List<RecurringChargeCandidateDto>,
        merchants: List<TopMerchantCandidateDto>,
        observedKeysByCategory: Map<String, Set<String>>,
        asOf: LocalDate,
    ): List<SpendingReviewDecisionDto> {
        if (decisions.isEmpty()) return emptyList()
        val emails = dataQueries.userEmails(decisions.map { it.decidedByUserId })
        return decisions.map { d ->
            val email = emails[d.decidedByUserId].orEmpty()
            val observedInCat = observedKeysByCategory[d.categoryCode].orEmpty()
            when (d.decisionType) {
                SpendingReviewDecisionType.split -> {
                    val anyPresent = d.memberKeys.any { it in observedInCat }
                    d.toDto(if (anyPresent) "active" else "inactive", null, email)
                }
                SpendingReviewDecisionType.not_recurring -> {
                    val anyPresent = d.memberKeys.any { it in observedInCat }
                    d.toDto(if (anyPresent) "active" else "inactive", null, email)
                }
                SpendingReviewDecisionType.keep, SpendingReviewDecisionType.compared -> {
                    val matchedRec = if (d.subjectKind == DecisionSubjectKind.recurring) {
                        recurring.firstOrNull { r -> d.matches(DecisionSubjectKind.recurring, r.categoryCode, r.memberKeys.toSet()) }
                    } else null
                    val matchedMerch = if (d.subjectKind == DecisionSubjectKind.merchant) {
                        merchants.firstOrNull { m -> d.matches(DecisionSubjectKind.merchant, m.categoryCode, m.memberKeys.toSet()) }
                    } else null
                    val currentCost = matchedRec?.monthlyCost ?: matchedMerch?.monthlyEquivalent
                    val matched = matchedRec != null || matchedMerch != null
                    val status = when {
                        !matched -> "inactive"
                        d.decisionType == SpendingReviewDecisionType.keep -> "active"
                        d.isComparedExpired(asOf) -> "expired"
                        d.isComparedResurfaced(currentCost) -> "resurfaced"
                        else -> "snoozed"
                    }
                    d.toDto(status, currentCost, email)
                }
            }
        }
    }

    @Transactional
    fun upsert(
        householdId: UUID,
        by: User,
        request: RecordSpendingReviewDecisionRequest,
    ): SpendingReviewDecisionDto {
        val categoryCode = request.categoryCode.trim()
        val displayLabel = request.displayLabel.trim()
        if (categoryCode.isBlank()) throw AppException.badRequest("INVALID_PARAMETER", "categoryCode")
        if (displayLabel.isBlank() || displayLabel.length > 200) throw AppException.badRequest("INVALID_PARAMETER", "displayLabel")

        val now = Instant.now(clock)
        val today = LocalDate.now(clock)

        val subjectKey: String
        val memberKeys: Set<String>
        val splitGroups: List<Set<String>>

        if (request.decisionType == SpendingReviewDecisionType.split) {
            val cleanedGroups = request.splitGroups
                .map { g -> g.map(PayeeVariantClusterer::canonicalKey).filter { it.isNotBlank() }.toSet() }
                .filter { it.isNotEmpty() }
            if (cleanedGroups.size < 2) {
                throw AppException.badRequest("INVALID_PARAMETER", "splitGroups")
            }
            val allKeys = cleanedGroups.flatMap { it }
            if (allKeys.size != allKeys.toSet().size) {
                throw AppException.badRequest("INVALID_PARAMETER", "splitGroups")
            }
            splitGroups = cleanedGroups
            memberKeys = allKeys.toSet()
            subjectKey = PayeeVariantClusterer.splitSubjectKey(categoryCode, splitGroups)
        } else {
            val rawKeys = if (request.memberKeys.isNotEmpty()) request.memberKeys else listOf(displayLabel)
            memberKeys = rawKeys.map(PayeeVariantClusterer::canonicalKey).filter { it.isNotBlank() }.toSet()
            if (memberKeys.isEmpty()) throw AppException.badRequest("INVALID_PARAMETER", "memberKeys")
            splitGroups = emptyList()
            subjectKey = PayeeVariantClusterer.stableSubjectKey(request.subjectKind.name, categoryCode, memberKeys)
        }

        val amount = request.amountAtDecision?.let(Money::normalize)
        val snoozeUntil = if (request.decisionType == SpendingReviewDecisionType.compared) {
            today.plusMonths(SpendingReviewConstants.DECISION_COMPARED_SNOOZE_MONTHS)
        } else null

        val existing = repo.findByHouseholdIdAndSubjectKey(householdId, subjectKey)
        val entity = if (existing != null) {
            existing.decisionType = request.decisionType
            existing.displayLabel = displayLabel
            existing.memberKeysJson = SpendingReviewDecisionCodec.encodeKeys(memberKeys)
            existing.splitGroupsJson = if (splitGroups.isNotEmpty()) SpendingReviewDecisionCodec.encodeSplitGroups(splitGroups) else null
            existing.amountAtDecision = amount
            existing.snoozeUntil = snoozeUntil
            existing.decidedByUserId = by.id
            existing.decidedAt = now
            repo.save(existing)
        } else {
            repo.save(
                SpendingReviewDecision(
                    householdId = householdId,
                    subjectKey = subjectKey,
                    subjectKind = request.subjectKind,
                    categoryCode = categoryCode,
                    decisionType = request.decisionType,
                    displayLabel = displayLabel,
                    memberKeysJson = SpendingReviewDecisionCodec.encodeKeys(memberKeys),
                    splitGroupsJson = if (splitGroups.isNotEmpty()) SpendingReviewDecisionCodec.encodeSplitGroups(splitGroups) else null,
                    amountAtDecision = amount,
                    snoozeUntil = snoozeUntil,
                    decidedByUserId = by.id,
                    decidedAt = now,
                ),
            )
        }

        val decoded = SpendingReviewDecisionCodec.decode(entity)
        val status = if (decoded.decisionType == SpendingReviewDecisionType.compared) "snoozed" else "active"
        return decoded.toDto(status = status, currentMonthlyCost = amount, decidedBy = by.email)
    }

    @Transactional
    fun delete(householdId: UUID, decisionId: UUID) {
        val existing = repo.findByIdAndHouseholdId(decisionId, householdId)
            ?: throw AppException.notFound("SPENDING_REVIEW_DECISION_NOT_FOUND")
        repo.delete(existing)
    }

    private fun DecodedDecision.toDto(
        status: String,
        currentMonthlyCost: BigDecimal?,
        decidedBy: String,
    ) = SpendingReviewDecisionDto(
        id = id,
        subjectKey = subjectKey,
        subjectKind = subjectKind,
        categoryCode = categoryCode,
        decisionType = decisionType,
        displayLabel = displayLabel,
        memberKeys = memberKeys.sorted(),
        splitGroups = splitGroups.map { it.sorted() },
        amountAtDecision = amountAtDecision,
        currentMonthlyCost = currentMonthlyCost,
        snoozeUntil = snoozeUntil,
        status = status,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )
}
