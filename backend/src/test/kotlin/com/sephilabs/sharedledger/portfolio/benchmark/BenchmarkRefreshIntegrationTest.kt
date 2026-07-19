package com.sephilabs.sharedledger.portfolio.benchmark

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.portfolio.StubPriceProviderConfig
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.math.BigDecimal
import java.time.LocalDate

/** Controllable benchmark source so refresh tests never perform real provider HTTP. */
class StubBenchmarkSource : BenchmarkSource {
    val history = mutableMapOf<String, MutableList<DailyPrice>>()
    val calls = mutableListOf<Triple<String, LocalDate, LocalDate>>()

    override fun dailyCloses(benchmark: Benchmark, from: LocalDate, to: LocalDate): List<DailyPrice> {
        calls += Triple(benchmark.key, from, to)
        return history[benchmark.key]?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) } ?: emptyList()
    }
}

@TestConfiguration
class StubBenchmarkSourceConfig {
    @Bean
    @Primary
    fun stubBenchmarkSource(): StubBenchmarkSource = StubBenchmarkSource()
}

@Import(StubPriceProviderConfig::class, StubBenchmarkSourceConfig::class)
class BenchmarkRefreshIntegrationTest @Autowired constructor(
    private val refresh: BenchmarkRefreshService,
    private val prices: BenchmarkPriceRepository,
    private val source: StubBenchmarkSource,
) : IntegrationTestBase() {

    private val today = LocalDate.of(2026, 6, 10)

    @Test
    fun `bootstraps then incrementally tails a benchmark series`() {
        source.history["sp500"] = mutableListOf(
            DailyPrice(LocalDate.of(2026, 6, 8), BigDecimal("100")),
            DailyPrice(LocalDate.of(2026, 6, 9), BigDecimal("101")),
        )

        // First run: nothing stored -> the whole lookback window is fetched (bootstrap).
        refresh.refresh(today)
        assertThat(closesOf("sp500")).containsExactly(
            LocalDate.of(2026, 6, 8) to "100",
            LocalDate.of(2026, 6, 9) to "101",
        )

        // A new close appears; the next run resumes from the last stored date and tails only.
        source.history.getValue("sp500").add(DailyPrice(today, BigDecimal("102")))
        refresh.refresh(today)
        assertThat(closesOf("sp500")).containsExactly(
            LocalDate.of(2026, 6, 8) to "100",
            LocalDate.of(2026, 6, 9) to "101",
            today to "102",
        )
    }

    private fun closesOf(key: String): List<Pair<LocalDate, String>> =
        prices.findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(
            key, LocalDate.of(2000, 1, 1), today,
        ).map { it.priceDate to it.close.stripTrailingZeros().toPlainString() }
}
