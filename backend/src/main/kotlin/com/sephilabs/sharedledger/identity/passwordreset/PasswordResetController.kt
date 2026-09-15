package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.observability.AppMetrics
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth/password-reset")
class PasswordResetController(
    private val service: PasswordResetService,
    private val dispatcher: PasswordResetDispatcher,
    private val rateLimiter: PasswordResetRateLimiter,
    private val metrics: AppMetrics,
) {

    /** Always 202 with the same body: whether the address matches an account is decided off-thread. */
    @PostMapping
    fun request(@Valid @RequestBody body: PasswordResetRequest, request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        service.requireAvailable()
        val ip = request.remoteAddr ?: "unknown"
        val email = body.email.trim().lowercase()
        if (!rateLimiter.tryAcquireIp(ip) || !rateLimiter.tryAcquireEmail(email)) {
            metrics.passwordResetRequested("rate_limited")
            throw AppException.tooManyRequests()
        }
        metrics.passwordResetRequested("accepted")
        dispatcher.dispatch(email)
        return ResponseEntity.accepted().body(mapOf("status" to "accepted"))
    }

    @PostMapping("/validate")
    fun validate(@Valid @RequestBody body: PasswordResetTokenRequest): Map<String, String> {
        service.requireAvailable()
        service.validate(body.token)
        return mapOf("status" to "valid")
    }

    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody body: PasswordResetConfirmRequest): Map<String, String> {
        service.requireAvailable()
        service.reset(body.token, body.newPassword)
        return mapOf("status" to "ok")
    }
}
