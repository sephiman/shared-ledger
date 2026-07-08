package com.sephilabs.sharedledger.networth.asset

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

/** Named asset types. Validated in the service layer (no DB CHECK constraint). */
enum class AssetType { property, vehicle, other }

/**
 * A named asset (house, car, …) that adds to net worth. Its value over time lives in its own
 * dated series ([AssetValueEntry]); this entity only carries identity + active flag.
 */
@Entity
@Table(name = "assets")
@SQLRestriction("deleted_at IS NULL")
class Asset(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "name", nullable = false, length = 120)
    var name: String,

    @Column(name = "type", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    var type: AssetType = AssetType.other,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()

/** One dated value in an asset's own value series (the source of truth for its worth over time). */
@Entity
@Table(name = "asset_value_entries")
@SQLRestriction("deleted_at IS NULL")
class AssetValueEntry(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "asset_id", nullable = false)
    var assetId: UUID,

    @Column(name = "value_date", nullable = false)
    var valueDate: LocalDate,

    @Column(name = "value", nullable = false, precision = 15, scale = 2)
    var value: BigDecimal,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
