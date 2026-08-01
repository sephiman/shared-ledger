package com.sephilabs.sharedledger.portfolio.price

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One observed price per (provider, provider_symbol, currency, price_date) — keyed by provider
 *  coordinates, not user symbols, so rows are shared across households. Non-trading days are never stored;
 *  reads forward-fill from the last row <= date. */
@Entity
@Table(name = "price_history")
class PricePoint(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "provider", nullable = false, length = 24)
    var provider: String,

    @Column(name = "provider_symbol", nullable = false, length = 120)
    var providerSymbol: String,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String,

    @Column(name = "price", nullable = false, precision = 28, scale = 12)
    var price: BigDecimal,

    @Column(name = "price_date", nullable = false)
    var priceDate: LocalDate,

    @Column(name = "as_of", nullable = false)
    var asOf: Instant,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
)
