package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.ZoneId
import java.util.UUID

class RefreshCadenceDescriptorTest {

    @EnableConfigurationProperties(AppProperties::class)
    private class PropsConfig

    @Test
    fun `Spring property binding of app portfolio crypto-refresh-cron drives forHolding cadence`() {
        val sui = holding(HoldingAssetClass.crypto, HoldingProvider.coingecko)
        val runner = ApplicationContextRunner().withUserConfiguration(PropsConfig::class.java)

        runner.withPropertyValues("app.portfolio.crypto-refresh-cron=0 5 * * * *").run { ctx ->
            val bound = ctx.getBean(AppProperties::class.java)
            assertThat(RefreshCadenceDescriptor.forHolding(sui, bound)?.interval).isEqualTo(1)
            assertThat(RefreshCadenceDescriptor.forHolding(sui, bound)?.unit).isEqualTo(RefreshCadenceUnit.HOURS)
        }

        runner.withPropertyValues("app.portfolio.crypto-refresh-cron=0 5 */3 * * *").run { ctx ->
            val bound = ctx.getBean(AppProperties::class.java)
            assertThat(RefreshCadenceDescriptor.forHolding(sui, bound)?.interval).isEqualTo(3)
            assertThat(RefreshCadenceDescriptor.forHolding(sui, bound)?.unit).isEqualTo(RefreshCadenceUnit.HOURS)
        }
    }

    private fun props(
        cryptoCron: String = "0 5 * * * *",
        equityRefreshCron: String = "0 10 22 * * *",
        equityIntradayCron: String = "0 15 * * * 1-5",
        equityProvider: AppProperties.EquityProviderKind = AppProperties.EquityProviderKind.YAHOO,
        displayTimezone: String = "Europe/Madrid",
    ) = AppProperties(
        scheduler = AppProperties.Scheduler(timezone = "UTC"),
        displayTimezone = displayTimezone,
        portfolio = AppProperties.Portfolio(
            cryptoRefreshCron = cryptoCron,
            equityRefreshCron = equityRefreshCron,
            equityIntradayRefreshCron = equityIntradayCron,
            equityProvider = equityProvider,
        ),
    )

    private fun holding(assetClass: HoldingAssetClass, provider: HoldingProvider?) = Holding(
        id = UUID.randomUUID(),
        householdId = UUID.randomUUID(),
        assetClass = assetClass,
        symbol = "TEST",
        nativeCurrency = "USD",
        provider = provider,
        providerSymbol = "test",
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )

    @Test
    fun `derives crypto cadence from two different cryptoRefreshCron values`() {
        val sui = holding(HoldingAssetClass.crypto, HoldingProvider.coingecko)

        val hourly = RefreshCadenceDescriptor.forHolding(sui, props(cryptoCron = "0 5 * * * *"))
        assertThat(hourly).isEqualTo(
            RefreshCadenceDto(
                unit = RefreshCadenceUnit.HOURS,
                interval = 1,
                weekdaysOnly = false,
                dailyTime = null,
                includesNightlyClose = false,
            ),
        )

        val everyTwoHours = RefreshCadenceDescriptor.forHolding(sui, props(cryptoCron = "0 5 */2 * * *"))
        assertThat(everyTwoHours).isEqualTo(
            RefreshCadenceDto(
                unit = RefreshCadenceUnit.HOURS,
                interval = 2,
                weekdaysOnly = false,
                dailyTime = null,
                includesNightlyClose = false,
            ),
        )
    }

    @Test
    fun `respects equityProvider allowsIntradayRefresh and weekdaysOnly detection`() {
        val etf = holding(HoldingAssetClass.etf, HoldingProvider.yahoo)

        val intraday = RefreshCadenceDescriptor.forHolding(
            etf,
            props(equityProvider = AppProperties.EquityProviderKind.YAHOO, equityIntradayCron = "0 15 */2 * * 1-5"),
        )
        assertThat(intraday).isEqualTo(
            RefreshCadenceDto(
                unit = RefreshCadenceUnit.HOURS,
                interval = 2,
                weekdaysOnly = true,
                dailyTime = null,
                includesNightlyClose = true,
            ),
        )

        val eodhdHolding = holding(HoldingAssetClass.etf, HoldingProvider.eodhd)
        val dailyOnly = RefreshCadenceDescriptor.forHolding(
            eodhdHolding,
            props(equityProvider = AppProperties.EquityProviderKind.EODHD, equityRefreshCron = "0 10 22 * * *"),
        )
        assertThat(dailyOnly?.unit).isEqualTo(RefreshCadenceUnit.DAYS)
        assertThat(dailyOnly?.interval).isEqualTo(1)
        assertThat(dailyOnly?.includesNightlyClose).isFalse()
        assertThat(dailyOnly?.dailyTime).contains("CE")
    }

    @Test
    fun `displayZoneId defaults to Europe-Madrid when blank and honors explicit zone`() {
        assertThat(props(displayTimezone = "").displayZoneId()).isEqualTo(ZoneId.of("Europe/Madrid"))
        assertThat(props(displayTimezone = "America/New_York").displayZoneId()).isEqualTo(ZoneId.of("America/New_York"))
    }
}
