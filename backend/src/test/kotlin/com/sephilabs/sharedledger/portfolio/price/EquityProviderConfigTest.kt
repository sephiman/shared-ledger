package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient

class EquityProviderConfigTest {

    private val builderProvider = object : ObjectProvider<RestClient.Builder> {
        override fun getObject(): RestClient.Builder = RestClient.builder()
    }

    private fun providerFor(kind: AppProperties.EquityProviderKind): EquityPriceProvider =
        EquityProviderConfig().equityPriceProvider(
            AppProperties(portfolio = AppProperties.Portfolio(equityProvider = kind)),
            builderProvider,
        )

    @Test
    fun `selects the adapter from config`() {
        assertThat(providerFor(AppProperties.EquityProviderKind.YAHOO)).isInstanceOf(YahooFinanceClient::class.java)
        assertThat(providerFor(AppProperties.EquityProviderKind.EODHD)).isInstanceOf(EodhdClient::class.java)
        assertThat(providerFor(AppProperties.EquityProviderKind.TWELVE_DATA)).isInstanceOf(TwelveDataClient::class.java)
    }

    @Test
    fun `maps to the matching holdings provider value`() {
        assertThat(AppProperties.EquityProviderKind.YAHOO.asHoldingProvider()).isEqualTo(HoldingProvider.yahoo)
        assertThat(AppProperties.EquityProviderKind.EODHD.asHoldingProvider()).isEqualTo(HoldingProvider.eodhd)
        assertThat(AppProperties.EquityProviderKind.TWELVE_DATA.asHoldingProvider()).isEqualTo(HoldingProvider.twelvedata)
    }
}
