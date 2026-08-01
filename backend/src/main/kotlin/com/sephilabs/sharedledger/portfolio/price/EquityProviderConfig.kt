package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/** Selects the equity price provider from config. YAHOO (keyless, no hard quota) is the default; EODHD and
 *  TWELVE_DATA are official fallbacks. Exactly one EquityPriceProvider bean exists, so the rest of the app
 *  — and the @Primary test stubs — stay oblivious to which adapter is active. */
@Configuration
class EquityProviderConfig {

    @Bean
    fun equityPriceProvider(
        props: AppProperties,
        restClientBuilderProvider: ObjectProvider<RestClient.Builder>,
    ): EquityPriceProvider = when (props.portfolio.equityProvider) {
        AppProperties.EquityProviderKind.YAHOO ->
            YahooFinanceClient(props, restClientBuilderProvider.getObject(), restClientBuilderProvider.getObject())
        AppProperties.EquityProviderKind.EODHD ->
            EodhdClient(props, restClientBuilderProvider.getObject())
        AppProperties.EquityProviderKind.TWELVE_DATA ->
            TwelveDataClient(props, restClientBuilderProvider.getObject())
    }
}

/** holdings.provider value corresponding to the configured equity provider. */
fun AppProperties.EquityProviderKind.asHoldingProvider(): HoldingProvider = when (this) {
    AppProperties.EquityProviderKind.YAHOO -> HoldingProvider.yahoo
    AppProperties.EquityProviderKind.EODHD -> HoldingProvider.eodhd
    AppProperties.EquityProviderKind.TWELVE_DATA -> HoldingProvider.twelvedata
}
