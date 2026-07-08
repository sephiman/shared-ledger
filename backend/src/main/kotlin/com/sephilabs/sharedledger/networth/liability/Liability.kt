package com.sephilabs.sharedledger.networth.liability

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.util.UUID

@Entity
@Table(name = "liabilities")
@SQLRestriction("deleted_at IS NULL")
class Liability(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "name", nullable = false, length = 120)
    var name: String,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    // When true, the balance is computed by the amortization schedule (parts/revisions/prepayments)
    // rather than the manual balance series. See the networth.amortization package.
    @Column(name = "amortizable", nullable = false)
    var amortizable: Boolean = false,

    // Day of month the instalment is charged (1-31; clamped to the month length). Null until set.
    @Column(name = "charge_day")
    var chargeDay: Int? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
