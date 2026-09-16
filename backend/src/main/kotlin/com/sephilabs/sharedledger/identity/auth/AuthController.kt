package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.HomePanel
import com.sephilabs.sharedledger.identity.user.PortfolioReturnBasis
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.mail.MailAvailability
import com.sephilabs.sharedledger.observability.AppMetrics
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val sessions: UserSessions,
    private val authService: AuthService,
    private val currentUser: CurrentUser,
    private val metrics: AppMetrics,
    private val rateLimiter: LoginRateLimiter,
    private val mail: MailAvailability,
) {

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> {
        // Touching the CsrfToken attribute forces Spring Security to materialize
        // it and write the XSRF-TOKEN cookie — the SPA needs this before any POST.
        return mapOf("headerName" to token.headerName, "parameterName" to token.parameterName)
    }

    @GetMapping("/features")
    fun features(): AuthFeaturesResponse =
        AuthFeaturesResponse(passwordReset = mail.enabled, emailChangeVerified = mail.enabled)

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        val ip = request.remoteAddr ?: "unknown"
        if (!rateLimiter.tryAcquire("login:$ip")) {
            metrics.loginAttempt("rate_limited")
            throw AppException.tooManyRequests()
        }
        try {
            sessions.establish(body.email, body.password, request, response)
            val user = users.findByEmailIgnoreCase(body.email) ?: throw BadCredentialsException("INVALID_CREDENTIALS")
            authService.recordLogin(user.id)
            metrics.loginAttempt("success")
            return ResponseEntity.ok(buildMe(user))
        } catch (ex: BadCredentialsException) {
            metrics.loginAttempt("failure")
            throw ex
        }
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): Map<String, String> {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return mapOf("status" to "ok")
    }

    @PostMapping("/register")
    fun register(
        @Valid @RequestBody body: RegisterRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        // Throttle per source IP (same limiter as login) so the endpoint can't be used to
        // brute-force invitation tokens or enumerate emails.
        val ip = request.remoteAddr ?: "unknown"
        if (!rateLimiter.tryAcquire("register:$ip")) throw AppException.tooManyRequests()
        val user = authService.register(body)
        // Establish the session immediately so registration is self-contained:
        // the new user is authenticated without depending on a follow-up /login.
        sessions.establish(user.email, body.password, request, response)
        authService.recordLogin(user.id)
        return ResponseEntity.status(201).body(buildMe(user))
    }

    @GetMapping("/me")
    fun me(): MeResponse = buildMe(currentUser.requireUser())

    @PatchMapping("/me")
    fun updateMe(@Valid @RequestBody body: MeUpdateRequest): MeResponse {
        val current = currentUser.requireUser()
        val updated = authService.updateLocale(current.id, body.locale)
        return buildMe(updated)
    }

    @PostMapping("/password")
    fun changePassword(@Valid @RequestBody body: PasswordChangeRequest): Map<String, String> {
        val current = currentUser.requireUser()
        authService.changePassword(current.id, body.currentPassword, body.newPassword)
        return mapOf("status" to "ok")
    }

    @PutMapping("/me/default-household")
    fun setDefaultHousehold(@Valid @RequestBody body: DefaultHouseholdRequest): MeResponse {
        val current = currentUser.requireUser()
        val updated = authService.setDefaultHousehold(current.id, body.householdId)
        return buildMe(updated)
    }

    @PutMapping("/me/home-panels")
    fun setHomePanels(@Valid @RequestBody body: HomePanelsRequest): MeResponse {
        val current = currentUser.requireUser()
        val updated = authService.setHiddenHomePanels(current.id, body.hiddenPanels)
        return buildMe(updated)
    }

    @PutMapping("/me/portfolio-return-basis")
    fun setPortfolioReturnBasis(@Valid @RequestBody body: PortfolioReturnBasisRequest): MeResponse {
        val current = currentUser.requireUser()
        val updated = authService.setPortfolioReturnBasis(current.id, body.basis)
        return buildMe(updated)
    }

    private fun buildMe(user: User): MeResponse {
        val memberships = members.findAllByIdUserId(user.id)
        val byId = households.findAllById(memberships.map { it.id.householdId }).associateBy { it.id }
        val list = memberships.mapNotNull { m ->
            byId[m.id.householdId]?.let { h ->
                HouseholdMembershipDto(h.id, h.name, h.currency, m.role.name)
            }
        }
        val defaultId = user.defaultHouseholdId?.takeIf { id -> list.any { it.householdId == id } }
        // Drop ids that are no longer valid panels so a removed panel can't linger in the payload.
        val hiddenPanels = user.hiddenHomePanels.split(',').filter { it in HomePanel.ids }
        // Fall back to the default if the stored value is ever unknown (e.g. a rolled-back enum).
        val returnBasis = user.portfolioReturnBasis.takeIf { it in PortfolioReturnBasis.ids } ?: PortfolioReturnBasis.DEFAULT.id
        return MeResponse(user.id, user.email, user.locale, defaultId, hiddenPanels, returnBasis, list)
    }
}
