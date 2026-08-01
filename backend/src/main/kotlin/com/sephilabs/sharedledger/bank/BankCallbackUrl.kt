package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI

/** The SCA return URL. Operator-level config (`ENABLE_BANKING_REDIRECT_URL`) because it identifies the
 *  instance, not a household. [BankService.startLink] and the credentials card must show the same value
 *  or every link fails at the provider. Blank falls back to Origin, then Referer, then the request host. */
@Component
class BankCallbackUrl(private val props: AppProperties) {

    fun current(): String = props.enableBanking.redirectUrl.trim().ifBlank { derived() }

    private fun derived(): String = publicOrigin() + CALLBACK_PATH

    private fun publicOrigin(): String {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            ?: return fromRequestHost()
        return browserOrigin(request) ?: fromRequestHost()
    }

    private fun browserOrigin(request: HttpServletRequest): String? {
        // "null" is what a browser sends for an opaque origin; it is not a URL.
        request.getHeader("Origin")?.takeIf { it.isNotBlank() && it != "null" }?.let { return it.trimEnd('/') }
        val referer = request.getHeader("Referer")?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { URI(referer) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
    }

    /** `server.forward-headers-strategy: native` makes this the public origin behind the proxy. */
    private fun fromRequestHost(): String =
        ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().trimEnd('/')

    private companion object {
        /** The SPA route registered in `App.tsx`. */
        const val CALLBACK_PATH = "/settings/banks/callback"
    }
}
