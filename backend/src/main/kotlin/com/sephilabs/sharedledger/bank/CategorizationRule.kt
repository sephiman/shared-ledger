package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.TimestampedEntity
import com.sephilabs.sharedledger.transaction.Direction
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
import java.util.UUID

/**
 * A categorisation rule: when [matchField] [matchOp] [matchValue], assign [categoryCode] +
 * [direction]. Lower [priority] wins. Rules with source=learned are created automatically when a
 * movement is confirmed with a category (remembered by counterparty for next time).
 */
@Entity
@Table(name = "bank_categorization_rules")
class CategorizationRule(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 16)
    var matchField: RuleField,

    @Enumerated(EnumType.STRING)
    @Column(name = "match_op", nullable = false, length = 16)
    var matchOp: RuleOp,

    @Column(name = "match_value", nullable = false, length = 255)
    var matchValue: String,

    @Column(name = "category_code", nullable = false, length = 64)
    var categoryCode: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: Direction,

    @Column(name = "priority", nullable = false)
    var priority: Int = 100,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    var source: RuleSource = RuleSource.manual,

    @Column(name = "created_by_user_id")
    var createdByUserId: UUID? = null,
) : TimestampedEntity()

interface CategorizationRuleRepository : JpaRepository<CategorizationRule, UUID> {

    @Modifying
    @Query(value = "DELETE FROM bank_categorization_rules WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): CategorizationRule?
    fun findAllByHouseholdIdOrderByPriorityAsc(householdId: UUID): List<CategorizationRule>
    fun findFirstByHouseholdIdAndMatchFieldAndMatchOpAndMatchValueIgnoreCase(
        householdId: UUID,
        matchField: RuleField,
        matchOp: RuleOp,
        matchValue: String,
    ): CategorizationRule?
}
