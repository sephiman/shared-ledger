package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.common.TimestampedEntity
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

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @jakarta.persistence.JoinColumn(name = "snapshot_id", insertable = false, updatable = false)
    var namedAssetValues: MutableList<SnapshotNamedAssetValue> = mutableListOf()
}

@Embeddable
data class SnapshotAssetValueId(
    @Column(name = "snapshot_id") var snapshotId: UUID = UUID.randomUUID(),
    @Column(name = "asset_class_code") var assetClassCode: String = "",
) : Serializable

const val VALUE_SOURCE_COMPUTED = "computed"
const val VALUE_SOURCE_OVERRIDDEN = "overridden"

// A scheduled snapshot copied this manual class forward from the previous snapshot.
const val VALUE_SOURCE_CARRIED_OVER = "carried_over"

@Entity
@Table(name = "snapshot_asset_values")
class SnapshotAssetValue(
    @EmbeddedId
    var id: SnapshotAssetValueId,

    @Column(name = "value", nullable = false, precision = 15, scale = 2)
    var value: BigDecimal,

    // 'computed' when auto-filled from portfolio holdings, 'overridden' when user-entered.
    @Column(name = "value_source", nullable = false, length = 16)
    var valueSource: String = VALUE_SOURCE_OVERRIDDEN,
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

@Embeddable
data class SnapshotNamedAssetValueId(
    @Column(name = "snapshot_id") var snapshotId: UUID = UUID.randomUUID(),
    @Column(name = "asset_id") var assetId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "snapshot_named_asset_values")
class SnapshotNamedAssetValue(
    @EmbeddedId
    var id: SnapshotNamedAssetValueId,

    @Column(name = "value", nullable = false, precision = 15, scale = 2)
    var value: BigDecimal,
)
