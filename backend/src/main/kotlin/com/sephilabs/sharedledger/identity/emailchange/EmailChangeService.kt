package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.auth.UserSessions
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.mail.MailAvailability
import com.sephilabs.sharedledger.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What the mail needs. [token] is the raw value: it goes into the mail and nowhere else. */
data class IssuedEmailChangeToken(
    val userId: UUID,
    val currentEmail: String,
    val newEmail: String,
    val locale: String,
    val token: String,
    val tokenHash: String,
)

/** The address actually moved, so the caller knows which sessions to rebuild. */
data class AppliedEmailChange(val previousEmail: String, val newEmail: String)

sealed interface EmailChangeOutcome {
    /** SMTP configured: nothing changed yet, a confirmation link is on its way to [issued]. */
    data class Pending(val issued: IssuedEmailChangeToken) : EmailChangeOutcome

    /** No SMTP: there is no channel to verify with, and no reset flow to protect either, so the password
     *  check alone carries the change. */
    data class Applied(val change: AppliedEmailChange) : EmailChangeOutcome
}

@Service
class EmailChangeService(
    private val tokens: EmailChangeTokenRepository,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val sessions: UserSessions,
    private val availability: MailAvailability,
    private val metrics: AppMetrics,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun request(userId: UUID, rawNewEmail: String, currentPassword: String): EmailChangeOutcome {
        val user = users.findById(userId).orElseThrow { AppException.unauthorized() }
        // The password is checked before the address is probed, so a 409 is only ever visible to someone
        // who already holds the credentials.
        if (!encoder.matches(currentPassword, user.passwordHash)) {
            metrics.emailChange("rejected")
            throw AppException.badRequest("PASSWORD_MISMATCH")
        }
        val newEmail = rawNewEmail.trim().lowercase()
        if (newEmail.equals(user.email, ignoreCase = true)) {
            metrics.emailChange("rejected")
            throw AppException.badRequest("EMAIL_UNCHANGED")
        }
        if (users.existsByEmailIgnoreCase(newEmail)) {
            metrics.emailChange("rejected")
            throw AppException.conflict("EMAIL_ALREADY_REGISTERED")
        }
        tokens.deleteAllByUserIdAndUsedAtIsNull(user.id)

        if (!availability.enabled) {
            val previous = user.email
            user.email = newEmail
            sessions.invalidateAll(previous)
            metrics.emailChange("applied")
            log.info("email_change_applied userId={} verified=false", user.id)
            return EmailChangeOutcome.Applied(AppliedEmailChange(previous, newEmail))
        }

        val token = SecureTokens.generate()
        val tokenHash = SecureTokens.hash(token)
        tokens.save(
            EmailChangeToken(
                userId = user.id,
                newEmail = newEmail,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plus(props.emailChange.ttlMinutes, ChronoUnit.MINUTES),
            ),
        )
        metrics.emailChange("requested")
        log.info("email_change_requested userId={} tokenHashPrefix={}", user.id, SecureTokens.logPrefix(tokenHash))
        return EmailChangeOutcome.Pending(
            IssuedEmailChangeToken(user.id, user.email, newEmail, user.locale, token, tokenHash),
        )
    }

    @Transactional
    fun confirm(token: String): AppliedEmailChange {
        val row = activeToken(token)
        val user = users.findById(row.userId).orElseThrow { invalidToken() }
        val newEmail = row.newEmail.lowercase()
        // Someone else may have registered the address between the request and the click.
        if (users.existsByEmailIgnoreCase(newEmail)) throw AppException.conflict("EMAIL_ALREADY_REGISTERED")
        val previous = user.email
        user.email = newEmail
        row.usedAt = Instant.now()
        // Every session is indexed by the old principal name and would stop resolving anyway; drop them so
        // the account is reachable only through the address that was just proven.
        sessions.invalidateAll(previous)
        metrics.emailChange("confirmed")
        log.info("email_change_confirmed userId={} tokenHashPrefix={}", user.id, SecureTokens.logPrefix(row.tokenHash))
        return AppliedEmailChange(previous, newEmail)
    }

    /** Called when the password changes by any route: a change still waiting on its link must not outlive
     *  the credentials that started it, or the notice to the old address is unactionable. */
    @Transactional
    fun cancelPending(userId: UUID) {
        val dropped = tokens.deleteAllByUserIdAndUsedAtIsNull(userId)
        if (dropped > 0) log.info("email_change_cancelled userId={} tokens={}", userId, dropped)
    }

    // One code for unknown, expired and used alike.
    private fun activeToken(token: String): EmailChangeToken {
        val row = tokens.findByTokenHash(SecureTokens.hash(token)) ?: throw invalidToken()
        if (!row.isActive(Instant.now())) throw invalidToken()
        return row
    }

    private fun invalidToken() = AppException.badRequest("EMAIL_CHANGE_TOKEN_INVALID")
}
