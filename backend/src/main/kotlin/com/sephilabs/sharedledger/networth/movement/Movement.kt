package com.sephilabs.sharedledger.networth.movement

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

enum class MovementType { contribution, withdrawal, debt_payment }

@Entity
@Table(name = "net_worth_movements")
@SQLRestriction("deleted_at IS NULL")
class NetWorthMovement(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "movement_date", nullable = false)
    var movementDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    var type: MovementType,

    @Column(name = "asset_class_code")
    var assetClassCode: String? = null,

    @Column(name = "liability_id")
    var liabilityId: UUID? = null,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
