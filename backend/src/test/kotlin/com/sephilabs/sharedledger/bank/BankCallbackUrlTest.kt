package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** The SCA redirect URL must be identical in the `/auth` payload, the credentials card and the operator's
 *  Enable Banking registration. These pin the precedence that keeps the three in step. */
class BankCallbackUrlTest {

    @AfterEach
    fun unbind() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `the configured redirect URL wins over anything the request says`() {
        bind { it.addHeader("Origin", "https://impostor.example") }

        val url = callbackUrl("https://ledger.example.com/settings/banks/callback").current()

        assertThat(url).isEqualTo("https://ledger.example.com/settings/banks/callback")
    }

    @Test
    fun `surrounding whitespace in the configured value is ignored`() {
        bind { }

        assertThat(callbackUrl("  https://ledger.example.com/settings/banks/callback \n").current())
            .isEqualTo("https://ledger.example.com/settings/banks/callback")
    }

    @Test
    fun `unset, it falls back to the browser's own origin`() {
        // A proxy that rewrites Host (the vite dev proxy does) would otherwise give a
        // plausible-looking URL pointing at the wrong origin.
        bind { it.addHeader("Origin", "https://ledger.example.com") }
        assertThat(callbackUrl("").current()).isEqualTo("https://ledger.example.com/settings/banks/callback")

        bind { it.addHeader("Referer", "https://ledger.example.com:8443/settings") }
        assertThat(callbackUrl("").current()).isEqualTo("https://ledger.example.com:8443/settings/banks/callback")

        bind { }
        assertThat(callbackUrl("").current()).isEqualTo("http://localhost/settings/banks/callback")
    }

    @Test
    fun `an opaque origin is not mistaken for a URL`() {
        bind { it.addHeader("Origin", "null") }

        assertThat(callbackUrl("").current()).isEqualTo("http://localhost/settings/banks/callback")
    }

    private fun callbackUrl(redirectUrl: String) =
        BankCallbackUrl(AppProperties(enableBanking = AppProperties.EnableBanking(redirectUrl = redirectUrl)))

    private fun bind(customize: (MockHttpServletRequest) -> Unit) {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest().also(customize)))
    }
}
