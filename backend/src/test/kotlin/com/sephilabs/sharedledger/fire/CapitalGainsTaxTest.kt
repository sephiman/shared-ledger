package com.sephilabs.sharedledger.fire

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

/** Hand-verifiable against the seeded Spanish savings scale (Ley 7/2024):
 *  19% to 6,000 — 21% to 50,000 — 23% to 200,000 — 27% to 300,000 — 30% above. */
class CapitalGainsTaxTest {

    private val brackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS

    @Test
    fun `progressive tax matches the bracket table`() {
        assertThat(CapitalGainsTax.taxOn(0.0, brackets)).isEqualTo(0.0)
        assertThat(CapitalGainsTax.taxOn(-5.0, brackets)).isEqualTo(0.0)
        // 6000 × 19%
        assertThat(CapitalGainsTax.taxOn(6000.0, brackets)).isCloseTo(1140.0, offset(0.01))
        // 1140 + 4000 × 21%
        assertThat(CapitalGainsTax.taxOn(10000.0, brackets)).isCloseTo(1980.0, offset(0.01))
        // 1140 + 44000 × 21% + 10000 × 23%
        assertThat(CapitalGainsTax.taxOn(60000.0, brackets)).isCloseTo(12680.0, offset(0.01))
        // 1140 + 9240 + 34500 + 27000 + 100000 × 30%
        assertThat(CapitalGainsTax.taxOn(400000.0, brackets)).isCloseTo(101880.0, offset(0.01))
    }

    @Test
    fun `gross up is the exact inverse of the tax`() {
        val compiled = CapitalGainsTax.compile(brackets)
        for (net in listOf(1000.0, 5000.0, 25000.0, 80000.0, 400000.0, 2_000_000.0)) {
            for (g in listOf(0.05, 0.25, 0.39, 0.5, 0.75, 1.0)) {
                val gross = compiled.grossUp(net, g)
                assertThat(gross - compiled.taxOn(gross * g))
                    .describedAs("net=%s g=%s", net, g)
                    .isCloseTo(net, offset(0.01))
                assertThat(gross).isGreaterThanOrEqualTo(net)
            }
        }
    }

    @Test
    fun `spec example - 25k net with a 39 percent gain share needs about 27_100 gross`() {
        val gross = CapitalGainsTax.grossUp(25000.0, 0.39, brackets)
        assertThat(gross).isCloseTo(27099.4, offset(1.0))
        assertThat(gross - 25000.0).isCloseTo(2099.4, offset(1.0))
    }

    @Test
    fun `no gain share or no brackets means no tax`() {
        assertThat(CapitalGainsTax.grossUp(25000.0, 0.0, brackets)).isEqualTo(25000.0)
        assertThat(CapitalGainsTax.grossUp(25000.0, 0.5, emptyList())).isEqualTo(25000.0)
        assertThat(CapitalGainsTax.grossUp(0.0, 0.5, brackets)).isEqualTo(0.0)
    }

    @Test
    fun `all gain withdrawal in the first bracket`() {
        // W = 3600 / (1 − 0.19) = 4444.44, gain stays under 6000.
        val gross = CapitalGainsTax.grossUp(3600.0, 1.0, brackets)
        assertThat(gross).isCloseTo(3600.0 / 0.81, offset(0.01))
    }

    @Test
    fun `gains below a nonzero first bound are untaxed`() {
        val highFloor = listOf(TaxBracket(java.math.BigDecimal("6000.00"), java.math.BigDecimal("21.0")))
        assertThat(CapitalGainsTax.grossUp(5000.0, 0.5, highFloor)).isEqualTo(5000.0)
        assertThat(CapitalGainsTax.taxOn(5000.0, highFloor)).isEqualTo(0.0)
    }
}
