package com.sephilabs.sharedledger.portfolio

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Per-holding valuation frozen when a net-worth snapshot is created. Write-once:
 * rows are only replaced wholesale when the snapshot itself is edited.
 * snapshot_id is a plain column (SQL FK ON DELETE CASCADE) — no JPA relation, so the
 * portfolio package never depends on networth.snapshot.
 */
@Entity
@Table(name = "holding_valuations")
class HoldingValuation(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "snapshot_id", nullable = false)
    var snapshotId: UUID,

    @Column(name = "holding_id", nullable = false)
    var holdingId: UUID,

    @Column(name = "quantity", nullable = false, precision = 28, scale = 12)
    var quantity: BigDecimal,

    @Column(name = "unit_price", precision = 28, scale = 12)
    var unitPrice: BigDecimal? = null,

    @Column(name = "price_currency", length = 3)
    var priceCurrency: String? = null,

    @Column(name = "price_as_of")
    var priceAsOf: LocalDate? = null,

    @Column(name = "fx_rate", precision = 18, scale = 8)
    var fxRate: BigDecimal? = null,

    @Column(name = "value_base", nullable = false, precision = 15, scale = 2)
    var valueBase: BigDecimal,

    @Column(name = "stale", nullable = false)
    var stale: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
