package com.sharedledger.recurring

import com.sharedledger.common.SoftDeletableEntity
import com.sharedledger.transaction.Direction
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

enum class Cadence { weekly, monthly, yearly }

@Entity
@Table(name = "recurring_templates")
@SQLRestriction("deleted_at IS NULL")
class RecurringTemplate(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: Direction,

    @Column(name = "category_code", nullable = false, length = 64)
    var categoryCode: String,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "description")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false, length = 16)
    var cadence: Cadence,

    @Column(name = "day_of_month")
    var dayOfMonth: Short? = null,

    @Column(name = "day_of_week")
    var dayOfWeek: Short? = null,

    @Column(name = "month_of_year")
    var monthOfYear: Short? = null,

    @Column(name = "day_of_month_yearly")
    var dayOfMonthYearly: Short? = null,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "last_materialized_through")
    var lastMaterializedThrough: LocalDate? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
