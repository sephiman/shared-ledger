package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.portfolio.StubPriceProviderConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/** Stubbed providers so the triggered gap-fill performs no real HTTP; the test executor runs it inline. */
@Import(StubPriceProviderConfig::class)
class ManualPriceRefreshIntegrationTest @Autowired constructor(
    private val service: ManualPriceRefreshService,
) : IntegrationTestBase() {

    @Test
    fun `first trigger runs and a rapid second is skipped by the cooldown`() {
        assertThat(service.trigger()).isTrue()
        // Immediately again: inside the 60s cooldown, so nothing is re-fetched.
        assertThat(service.trigger()).isFalse()
    }
}
