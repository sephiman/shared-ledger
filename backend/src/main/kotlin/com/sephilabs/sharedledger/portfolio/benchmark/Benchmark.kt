package com.sephilabs.sharedledger.portfolio.benchmark

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

/** How the refresh job sources a benchmark's daily closes. */
enum class BenchmarkKind { equity, crypto }

/**
 * A reference index or asset the user can overlay on the portfolio ROI (TWR) chart.
 * The row is the extension point: a new benchmark is one seeded record (plus an i18n
 * label) — the chart, endpoints and refresh job are all data-driven off this table.
 * Closes are stored in [currency] and converted to the base currency at read time.
 */
@Entity
@Table(name = "benchmark")
class Benchmark(
    @Id
    @Column(name = "key", nullable = false, updatable = false, length = 32)
    var key: String,

    @Column(name = "source_provider", nullable = false, length = 24)
    var sourceProvider: String,

    @Column(name = "source_symbol", nullable = false, length = 120)
    var sourceSymbol: String,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    var kind: BenchmarkKind,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
