package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI

/**
 * The URL Enable Banking sends the holder back to after SCA. It identifies the *instance*, not a
 * household, so it stays operator configuration (`ENABLE_BANKING_REDIRECT_URL`) — every household
 * registers this same value in its own EB application.
 *
 * One source for two consumers that must agree: [BankService.startLink] sends it in the `/auth`
 * payload and the credentials card shows it as "register this"; if they diverged every link would
 * fail at the provider.
 *
 * Blank falls back to asking the *browser* what this instance is (`Origin`, then `Referer`, then the
 * request host), which keeps local development working and survives a proxy that rewrites `Host` or
 * drops `X-Forwarded-Proto`.
 */
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
