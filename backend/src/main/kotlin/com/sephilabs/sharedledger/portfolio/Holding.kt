package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.util.UUID

enum class HoldingAssetClass { crypto, etf, stock, fund }
enum class HoldingProvider { coingecko, yahoo, eodhd, twelvedata }

val ASSET_CLASS_TO_SNAPSHOT_CODE: Map<HoldingAssetClass, String> = mapOf(
    HoldingAssetClass.crypto to "crypto",
    HoldingAssetClass.etf to "etfs",
    HoldingAssetClass.stock to "stocks",
    HoldingAssetClass.fund to "fund",
)

@Entity
@Table(name = "holdings")
@SQLRestriction("deleted_at IS NULL")
class Holding(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 16)
    var assetClass: HoldingAssetClass,

    @Column(name = "symbol", nullable = false, length = 32)
    var symbol: String,

    @Column(name = "label", length = 120)
    var label: String? = null,

    @Column(name = "native_currency", nullable = false, length = 3)
    var nativeCurrency: String,

    @Column(name = "isin", length = 12)
    var isin: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 24)
    var provider: HoldingProvider? = null,

    @Column(name = "provider_symbol", length = 120)
    var providerSymbol: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity() {
    val linked: Boolean get() = provider != null && providerSymbol != null
}
