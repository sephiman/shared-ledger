package com.sephilabs.sharedledger.portfolio.benchmark

import org.springframework.data.jpa.repository.JpaRepository

interface BenchmarkRepository : JpaRepository<Benchmark, String> {

    fun findAllByEnabledTrueOrderBySortOrderAsc(): List<Benchmark>
}
