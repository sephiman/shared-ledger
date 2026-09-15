package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.auth.UserSessions
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A freshly issued token together with what the mail needs. [token] is the raw value: it goes into the mail
 *  and nowhere else. */
data class IssuedResetToken(
    val userId: UUID,
    val email: String,
    val locale: String,
    val token: String,
    val tokenHash: String,
)

@Service
class PasswordResetService(
    private val tokens: PasswordResetTokenRepository,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val sessions: UserSessions,
    private val availability: PasswordResetAvailability,
    private val metrics: AppMetrics,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun requireAvailable() {
        if (!availability.enabled) throw AppException.notFound()
    }

    /** Null when no account matches — the caller stays silent about it. Any outstanding unused token of the
     *  user is dropped first, so only the latest mailed link works. */
    @Transactional
    fun issueToken(normalizedEmail: String): IssuedResetToken? {
        val user = users.findByEmailIgnoreCase(normalizedEmail) ?: return null
        tokens.deleteAllByUserIdAndUsedAtIsNull(user.id)
        val token = SecureTokens.generate()
        val tokenHash = SecureTokens.hash(token)
        tokens.save(
            PasswordResetToken(
                userId = user.id,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plus(props.passwordReset.ttlMinutes, ChronoUnit.MINUTES),
            ),
        )
        return IssuedResetToken(user.id, user.email, user.locale, token, tokenHash)
    }

    @Transactional(readOnly = true)
    fun validate(token: String) {
        activeToken(token)
    }

    @Transactional
    fun reset(token: String, newPassword: String) {
        val row = activeToken(token)
        val user = users.findById(row.userId).orElseThrow { invalidToken() }
        user.passwordHash = encoder.encode(newPassword)!!
        row.usedAt = Instant.now()
        // Whoever prompted the reset may hold a live session; none of them survives the new password.
        sessions.invalidateAll(user.email)
        metrics.passwordResetCompleted()
        log.info("password_reset_completed userId={} tokenHashPrefix={}", user.id, SecureTokens.logPrefix(row.tokenHash))
    }

    // One code for unknown, expired and used alike: which of the three it was is nobody's business.
    private fun activeToken(token: String): PasswordResetToken {
        val row = tokens.findByTokenHash(SecureTokens.hash(token)) ?: throw invalidToken()
        if (!row.isActive(Instant.now())) throw invalidToken()
        return row
    }

    private fun invalidToken() = AppException.badRequest("PASSWORD_RESET_TOKEN_INVALID")
}
