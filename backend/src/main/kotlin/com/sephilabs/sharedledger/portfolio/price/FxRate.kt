package com.sephilabs.sharedledger.portfolio.price

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Daily FX rate from base_currency into quote_currency. Only business-day observations are stored; reads
 *  forward-fill from the last row <= date. */
@Entity
@Table(name = "fx_rates")
class FxRate(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "provider", nullable = false, length = 24)
    var provider: String,

    @Column(name = "base_currency", nullable = false, length = 3)
    var baseCurrency: String,

    @Column(name = "quote_currency", nullable = false, length = 3)
    var quoteCurrency: String,

    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    var rate: BigDecimal,

    @Column(name = "rate_date", nullable = false)
    var rateDate: LocalDate,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
)
