package com.sephilabs.sharedledger.portfolio

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

enum class LotType { BUY, SELL }

/**
 * One BUY/SELL movement of the holding's ledger. Net quantity, remaining cost basis
 * and realized P&L are computed by replaying the ledger — never stored.
 */
@Entity
@Table(name = "holding_lots")
@SQLRestriction("deleted_at IS NULL")
class HoldingLot(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "holding_id", nullable = false)
    var holdingId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 4)
    var type: LotType = LotType.BUY,

    @Column(name = "traded_on", nullable = false)
    var tradedOn: LocalDate,

    @Column(name = "quantity", nullable = false, precision = 28, scale = 12)
    var quantity: BigDecimal,

    // Purchase price for BUY, sale price for SELL.
    @Column(name = "unit_price", nullable = false, precision = 28, scale = 12)
    var unitPrice: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String,

    @Column(name = "fee", precision = 28, scale = 12)
    var fee: BigDecimal? = null,

    // Frozen when the trade is registered; 1 when currency is the household base currency.
    @Column(name = "fx_rate_to_base", nullable = false, precision = 18, scale = 8)
    var fxRateToBase: BigDecimal,

    @Column(name = "note", length = 500)
    var note: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
