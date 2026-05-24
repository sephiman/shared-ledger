package com.sharedledger.networth.snapshot

import com.sharedledger.common.TimestampedEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "snapshots")
class Snapshot(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate,

    @Column(name = "note")
    var note: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : TimestampedEntity() {

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @jakarta.persistence.JoinColumn(name = "snapshot_id", insertable = false, updatable = false)
    @OrderBy("id.assetClassCode ASC")
    var assetValues: MutableList<SnapshotAssetValue> = mutableListOf()

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @jakarta.persistence.JoinColumn(name = "snapshot_id", insertable = false, updatable = false)
    var liabilityBalances: MutableList<SnapshotLiabilityBalance> = mutableListOf()
}

@Embeddable
data class SnapshotAssetValueId(
    @Column(name = "snapshot_id") var snapshotId: UUID = UUID.randomUUID(),
    @Column(name = "asset_class_code") var assetClassCode: String = "",
) : Serializable

@Entity
@Table(name = "snapshot_asset_values")
class SnapshotAssetValue(
    @EmbeddedId
    var id: SnapshotAssetValueId,

    @Column(name = "value", nullable = false, precision = 15, scale = 2)
    var value: BigDecimal,
)

@Embeddable
data class SnapshotLiabilityBalanceId(
    @Column(name = "snapshot_id") var snapshotId: UUID = UUID.randomUUID(),
    @Column(name = "liability_id") var liabilityId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "snapshot_liability_balances")
class SnapshotLiabilityBalance(
    @EmbeddedId
    var id: SnapshotLiabilityBalanceId,

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    var balance: BigDecimal,
)
