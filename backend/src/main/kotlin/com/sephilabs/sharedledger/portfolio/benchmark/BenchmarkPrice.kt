package com.sephilabs.sharedledger.portfolio.benchmark

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One observed close per (benchmark_key, price_date), in the benchmark's own currency.
 * Non-trading days are never stored; reads forward-fill from the last row <= date.
 */
@Entity
@Table(name = "benchmark_price")
class BenchmarkPrice(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "benchmark_key", nullable = false, length = 32)
    var benchmarkKey: String,

    @Column(name = "price_date", nullable = false)
    var priceDate: LocalDate,

    @Column(name = "close", nullable = false, precision = 28, scale = 12)
    var close: BigDecimal,

    @Column(name = "as_of", nullable = false)
    var asOf: Instant,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
)
