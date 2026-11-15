package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.notification.TelegramMessageFormatter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

class PriceAlertFormatterTest @Autowired constructor(
    private val messages: Messages,
    private val categories: CategoryService,
) : IntegrationTestBase() {

    private fun formatter() = TelegramMessageFormatter(
        messages,
        categories,
        AppProperties(publicUrl = "https://ledger.example.com", displayTimezone = "Europe/Madrid"),
    )

    @Test
    fun `formats alert in English with display zone, position stats, note, and link`() {
        val fmt = formatter()
        val msg = fmt.formatPriceAlert(
            symbol = "SUI",
            label = "Sui",
            direction = AlertDirection.ABOVE,
            threshold = BigDecimal("2.80"),
            priceCurrency = "USD",
            firedPrice = BigDecimal("2.845"),
            priceObservedAt = Instant.parse("2026-07-15T14:05:00Z"),
            quantity = BigDecimal("1500.5"),
            currentValueBase = BigDecimal("3950.25"),
            unrealizedPnlBase = BigDecimal("420.10"),
            unrealizedPnlPct = BigDecimal("0.119"),
            baseCurrency = "EUR",
            note = "take profit 50%",
            locale = Locale.ENGLISH,
        )

        assertThat(msg).contains("SUI (Sui)")
        assertThat(msg).contains("\$2.84")
        assertThat(msg).contains("15 Jul 2026, 16:05 CEST")
        assertThat(msg).doesNotContain("14:05 UTC")
        assertThat(msg).contains("≥ \$2.80")
        assertThat(msg).contains("1,500.5")
        assertThat(msg).contains("take profit 50%")
        assertThat(msg).contains("https://ledger.example.com/networth?tab=holdings")

        val tiny = fmt.formatPriceAlert(
            symbol = "PEPE",
            label = "Pepe",
            direction = AlertDirection.ABOVE,
            threshold = BigDecimal("0.000125"),
            priceCurrency = "USD",
            firedPrice = BigDecimal("0.000126"),
            priceObservedAt = Instant.parse("2026-07-15T14:05:00Z"),
            quantity = BigDecimal("100000"),
            currentValueBase = BigDecimal("11.00"),
            unrealizedPnlBase = BigDecimal("1.00"),
            unrealizedPnlPct = BigDecimal("0.10"),
            baseCurrency = "EUR",
            note = null,
            locale = Locale.ENGLISH,
        )
        assertThat(tiny).contains("\$0.000126").contains("\$0.000125")
    }

    @Test
    fun `formats Spanish short months without trailing CLDR dot and omits blank note`() {
        val fmt = formatter()
        val msg = fmt.formatPriceAlert(
            symbol = "BTC",
            label = null,
            direction = AlertDirection.BELOW,
            threshold = BigDecimal("50000"),
            priceCurrency = "EUR",
            firedPrice = BigDecimal("49500"),
            priceObservedAt = Instant.parse("2026-09-10T10:00:00Z"),
            quantity = BigDecimal("0.25"),
            currentValueBase = BigDecimal("12375.00"),
            unrealizedPnlBase = BigDecimal("-125.00"),
            unrealizedPnlPct = BigDecimal("-0.01"),
            baseCurrency = "EUR",
            note = null,
            locale = Locale.forLanguageTag("es"),
        )

        assertThat(msg).contains("10 sep 2026, 12:00 CEST")
        assertThat(msg).doesNotContain("sept.")
        assertThat(msg).contains("≤")
        assertThat(msg).contains("-125,00")
        assertThat(msg).doesNotContain("Nota:")
    }
}
