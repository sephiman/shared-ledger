package com.sephilabs.sharedledger.portfolio.benchmark

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface BenchmarkPriceRepository : JpaRepository<BenchmarkPrice, UUID> {

    fun findByBenchmarkKeyAndPriceDate(benchmarkKey: String, priceDate: LocalDate): BenchmarkPrice?

    fun findFirstByBenchmarkKeyAndPriceDateLessThanEqualOrderByPriceDateDesc(
        benchmarkKey: String,
        priceDate: LocalDate,
    ): BenchmarkPrice?

    fun findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(
        benchmarkKey: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BenchmarkPrice>

    @Query("SELECT MIN(p.priceDate) FROM BenchmarkPrice p WHERE p.benchmarkKey = :key")
    fun findMinPriceDate(@Param("key") key: String): LocalDate?

    @Query("SELECT MAX(p.priceDate) FROM BenchmarkPrice p WHERE p.benchmarkKey = :key")
    fun findMaxPriceDate(@Param("key") key: String): LocalDate?
}
