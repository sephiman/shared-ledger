package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class Direction { income, expense }

@Entity
@Table(name = "transactions")
@SQLRestriction("deleted_at IS NULL")
class Transaction(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "occurrence_date", nullable = false)
    var occurrenceDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: Direction,

    @Column(name = "category_code", nullable = false, length = 64)
    var categoryCode: String,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "recurring_template_id")
    var recurringTemplateId: UUID? = null,

    /** Money coming back for a past purchase: an expense with a negative [amount], so it nets its category
     *  and month instead of inflating income. */
    @Column(name = "is_refund", nullable = false)
    var isRefund: Boolean = false,

    /** The expense this refund nets against, when known. A raw id, not a relation: the target is
     *  soft-deletable (a mapped association would silently read null) and the link is optional. */
    @Column(name = "refund_of_transaction_id")
    var refundOfTransactionId: UUID? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
