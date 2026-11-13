package com.sephilabs.sharedledger.fire.history

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CoverageAttributionTest {

    private fun endpoint(wealth: String, target: String) = CoverageAttribution.Endpoint(BigDecimal(wealth), BigDecimal(target))

    @Test
    fun `parts add up to the change in coverage, with the cross term shared`() {
        // 100k of 500k (20 %) → 130k of 520k (25 %): +5 points.
        val parts = CoverageAttribution.split(endpoint("100000", "500000"), endpoint("130000", "520000"), BigDecimal("20000"))
        assertThat(parts.total).isCloseTo(5.0, Offset.offset(1e-12))
        // Need: ½(100k + 130k) × (1/520k − 1/500k) × 100
        assertThat(parts.need).isCloseTo(0.5 * 230_000 * (1.0 / 520_000 - 1.0 / 500_000) * 100, Offset.offset(1e-12))
        // Contributions: 20k × ½(1/500k + 1/520k) × 100
        assertThat(parts.contributed!!).isCloseTo(20_000 * 0.5 * (1.0 / 500_000 + 1.0 / 520_000) * 100, Offset.offset(1e-12))
        assertThat(parts.contributed + parts.market!! + parts.need).isCloseTo(parts.total, Offset.offset(1e-12))
    }

    @Test
    fun `the split does not depend on the order the causes are measured in`() {
        val forward = CoverageAttribution.split(endpoint("100000", "500000"), endpoint("130000", "520000"), BigDecimal("20000"))
        val backward = CoverageAttribution.split(endpoint("130000", "520000"), endpoint("100000", "500000"), BigDecimal("-20000"))
        assertThat(backward.need).isCloseTo(-forward.need, Offset.offset(1e-12))
        assertThat(backward.contributed!!).isCloseTo(-forward.contributed!!, Offset.offset(1e-12))
        assertThat(backward.market!!).isCloseTo(-forward.market!!, Offset.offset(1e-12))
    }

    @Test
    fun `without contributions the wealth part stays merged`() {
        val parts = CoverageAttribution.split(endpoint("100000", "500000"), endpoint("130000", "520000"), null)
        assertThat(parts.contributed).isNull()
        assertThat(parts.market).isNull()
        assertThat(parts.wealth + parts.need).isCloseTo(parts.total, Offset.offset(1e-12))
    }

    @Test
    fun `rounded parts add up to the rounded total`() {
        // 1.04 + 1.04 + 1.04 = 3.12 → naive rounding shows 1.0 + 1.0 + 1.0 = 3.0 against a 3.1 total.
        val shown = CoverageAttribution.roundToTenths(listOf(1.04, 1.04, 1.04), 3.12)
        assertThat(shown.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3.1")
        val negatives = CoverageAttribution.roundToTenths(listOf(2.13, 1.44, -0.56), 3.01)
        assertThat(negatives.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3.0")
    }
}
