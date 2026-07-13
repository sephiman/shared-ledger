package com.sephilabs.sharedledger.observability

import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MdcRequestContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val requestId = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val clientIp = resolveClientIp(request)

        MDC.put("requestId", requestId)
        MDC.put("clientIp", clientIp)
        response.setHeader("X-Request-Id", requestId)

        try {
            chain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }

    // RemoteIpValve (forward-headers-strategy: native) has already resolved remoteAddr to the real
    // client behind trusted proxies, so we trust it rather than re-parsing X-Forwarded-For (which a
    // client could spoof).
    private fun resolveClientIp(request: HttpServletRequest): String =
        request.remoteAddr ?: "unknown"
}

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
class AuthMdcFilter(private val users: UserRepository) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        if (principal is org.springframework.security.core.userdetails.UserDetails) {
            users.findByEmailIgnoreCase(principal.username)?.let { MDC.put("userId", it.id.toString()) }
        }
        chain.doFilter(request, response)
    }
}
