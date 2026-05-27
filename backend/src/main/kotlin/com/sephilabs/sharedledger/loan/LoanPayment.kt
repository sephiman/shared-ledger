package com.sephilabs.sharedledger.loan

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "loan_payments")
@SQLRestriction("deleted_at IS NULL")
class LoanPayment(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "loan_id", nullable = false)
    var loanId: UUID,

    @Column(name = "payment_date", nullable = false)
    var paymentDate: LocalDate,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "description", length = 500)
    var description: String? = null,

    @Column(name = "schedule_id")
    var scheduleId: UUID? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
