package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.identity.auth.UserSessions
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth/email")
class EmailChangeController(
    private val service: EmailChangeService,
    private val dispatcher: EmailChangeDispatcher,
    private val rateLimiter: EmailChangeRateLimiter,
    private val sessions: UserSessions,
    private val currentUser: CurrentUser,
) {

    @PostMapping
    fun request(
        @Valid @RequestBody body: EmailChangeRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<EmailChangeResponse> {
        val user = currentUser.requireUser()
        if (!rateLimiter.tryAcquire(user.id)) throw AppException.tooManyRequests()
        return when (val outcome = service.request(user.id, body.newEmail, body.currentPassword)) {
            is EmailChangeOutcome.Pending -> {
                dispatcher.dispatch(outcome.issued)
                ResponseEntity.accepted().body(EmailChangeResponse("pending", outcome.issued.newEmail))
            }

            is EmailChangeOutcome.Applied -> {
                // The service already dropped this user's session rows, including the caller's. End the
                // in-flight one too and open a fresh session under the new principal, so the SPA keeps
                // working instead of 401-ing on its next request.
                request.getSession(false)?.invalidate()
                sessions.establish(outcome.change.newEmail, body.currentPassword, request, response)
                ResponseEntity.ok(EmailChangeResponse("applied", outcome.change.newEmail))
            }
        }
    }

    /** Public: the link is usually opened on the device that reads the mail, not the one signed in. */
    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody body: EmailChangeTokenRequest): EmailChangeResponse {
        val applied = service.confirm(body.token)
        return EmailChangeResponse("applied", applied.newEmail)
    }
}
