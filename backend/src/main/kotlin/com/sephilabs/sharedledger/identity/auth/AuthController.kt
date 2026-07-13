package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.observability.AppMetrics
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val authManager: AuthenticationManager,
    private val authService: AuthService,
    private val currentUser: CurrentUser,
    private val metrics: AppMetrics,
    private val rateLimiter: LoginRateLimiter,
) {
    private val contextRepo = HttpSessionSecurityContextRepository()

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> {
        // Touching the CsrfToken attribute forces Spring Security to materialize
        // it and write the XSRF-TOKEN cookie — the SPA needs this before any POST.
        return mapOf("headerName" to token.headerName, "parameterName" to token.parameterName)
    }

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
            openSession(body.email, body.password, request, response)
            val user = users.findByEmailIgnoreCase(body.email) ?: throw BadCredentialsException("INVALID_CREDENTIALS")
            authService.recordLogin(user.id)
            metrics.loginAttempt("success")
            return ResponseEntity.ok(buildMe(user))
        } catch (ex: BadCredentialsException) {
            metrics.loginAttempt("failure")
            throw ex
        }
    }

    /**
     * Authenticate and persist the SecurityContext into the HTTP session so the
     * SESSION cookie is written on the response. Shared by login and register —
     * registering must drop the new user into a real authenticated session, not
     * rely on the client issuing a follow-up /login.
     */
    private fun openSession(email: String, password: String, request: HttpServletRequest, response: HttpServletResponse) {
        val auth = authManager.authenticate(UsernamePasswordAuthenticationToken(email, password))
        // Session-fixation defense: if the client already had a session, rotate its id now that
        // authentication succeeded, so a pre-seeded session id can't be promoted to an authenticated
        // one. (When there's no prior session, saveContext mints a fresh one below.)
        if (request.getSession(false) != null) request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = auth }
        SecurityContextHolder.setContext(context)
        contextRepo.saveContext(context, request, response)
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
        openSession(user.email, body.password, request, response)
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

    private fun buildMe(user: User): MeResponse {
        val memberships = members.findAllByIdUserId(user.id)
        val byId = households.findAllById(memberships.map { it.id.householdId }).associateBy { it.id }
        val list = memberships.mapNotNull { m ->
            byId[m.id.householdId]?.let { h ->
                HouseholdMembershipDto(h.id, h.name, h.currency, m.role.name)
            }
        }
        val defaultId = user.defaultHouseholdId?.takeIf { id -> list.any { it.householdId == id } }
        return MeResponse(user.id, user.email, user.locale, defaultId, list)
    }
}
