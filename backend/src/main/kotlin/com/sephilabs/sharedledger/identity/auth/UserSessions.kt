package com.sephilabs.sharedledger.identity.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.stereotype.Component

/** The server-side session rows (SPRING_SESSION) of one user. Every session is indexed by its principal
 *  name — the login email — which is why an email change has to rebuild them rather than leave them
 *  pointing at an address the user no longer has. */
@Component
class UserSessions(
    private val sessions: JdbcIndexedSessionRepository,
    private val authManager: AuthenticationManager,
) {
    private val contextRepo = HttpSessionSecurityContextRepository()

    /** Authenticates the credentials and persists the SecurityContext into the session, so the SESSION
     *  cookie is written. Shared by login, register and the direct email change. */
    fun establish(email: String, password: String, request: HttpServletRequest, response: HttpServletResponse) {
        val auth = authManager.authenticate(UsernamePasswordAuthenticationToken(email, password))
        // Session-fixation defense: if the client already had a session, rotate its id now that
        // authentication succeeded, so a pre-seeded session id can't be promoted to an authenticated one.
        // (When there's no prior session, saveContext mints a fresh one below.)
        if (request.getSession(false) != null) request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = auth }
        SecurityContextHolder.setContext(context)
        contextRepo.saveContext(context, request, response)
    }

    /** Deletes every server-side session of [principalName] (the login email) — the SPRING_SESSION rows. */
    fun invalidateAll(principalName: String) {
        sessions.findByPrincipalName(principalName).keys.forEach(sessions::deleteById)
    }
}
