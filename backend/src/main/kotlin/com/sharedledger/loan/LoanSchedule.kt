package com.sharedledger.loan

import com.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "loan_schedules")
class LoanSchedule(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "loan_id", nullable = false)
    var loanId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 16)
    var frequency: LoanFrequency,

    @Column(name = "day_of_week")
    var dayOfWeek: Short? = null,

    @Column(name = "day_of_month")
    var dayOfMonth: Short? = null,

    @Column(name = "expected_amount", nullable = false, precision = 15, scale = 2)
    var expectedAmount: BigDecimal,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "last_materialized_through")
    var lastMaterializedThrough: LocalDate? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : TimestampedEntity()
