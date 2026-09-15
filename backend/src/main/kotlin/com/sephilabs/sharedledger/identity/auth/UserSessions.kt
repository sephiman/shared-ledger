package com.sephilabs.sharedledger.identity.auth

import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.stereotype.Component

@Component
class UserSessions(private val sessions: JdbcIndexedSessionRepository) {

    /** Deletes every server-side session of [principalName] (the login email) — the SPRING_SESSION rows. */
    fun invalidateAll(principalName: String) {
        sessions.findByPrincipalName(principalName).keys.forEach(sessions::deleteById)
    }
}
